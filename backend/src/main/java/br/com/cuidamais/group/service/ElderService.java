package br.com.cuidamais.group.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.dto.AddressRequest;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.CareGroup;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.domain.EmergencyContact;
import br.com.cuidamais.group.dto.ElderPhotoContent;
import br.com.cuidamais.group.dto.ElderRequest;
import br.com.cuidamais.group.dto.ElderResponse;
import br.com.cuidamais.group.dto.EmergencyContactRequest;
import br.com.cuidamais.group.exception.GroupAlreadyExistsException;
import br.com.cuidamais.group.exception.PhotoTooLargeException;
import br.com.cuidamais.group.exception.UnsupportedPhotoFormatException;
import br.com.cuidamais.group.repository.CareGroupRepository;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.group.repository.EmergencyContactRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.GroupDeletedEmail;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.exception.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orquestra o cadastro, a leitura, a edição e a foto do idoso de um grupo (T-030, T-031, D-05
 * rev. 2, D-07: a regra de negócio mora nas entidades — {@link CareGroup}, {@link Elder}, {@link
 * br.com.cuidamais.membership.domain.Membership} — este serviço só decide <strong>quando</strong>
 * chamá-las e persiste o resultado).
 *
 * <p>Todo método é {@code @Transactional}: {@link #createElder} precisa que grupo, idoso,
 * contatos e membership nasçam juntos ou nada nasça (AC-001.2, D-05); {@link #updateElder}
 * precisa que a substituição completa dos contatos de emergência (apagar todos, recriar a lista
 * nova) seja atômica (AC-002.3, AC-002.9); {@link #uploadPhoto}/{@link #removePhoto} (AC-002.6,
 * D-30) precisam que a troca do arquivo em disco ({@link ElderPhotoStorage}) e a atualização do
 * {@code photoPath} na entidade sejam consistentes entre si.
 */
@Service
public class ElderService {

    /** 5 MB (D-30): a borda exata ({@code 5_242_880} bytes) é aceita, um byte a mais já é 413. */
    private static final long MAX_PHOTO_SIZE_BYTES = 5L * 1024 * 1024;

    private final CareGroupRepository careGroups;
    private final ElderRepository elders;
    private final EmergencyContactRepository emergencyContacts;
    private final MembershipRepository memberships;
    private final ElderPhotoStorage photoStorage;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public ElderService(
            CareGroupRepository careGroups,
            ElderRepository elders,
            EmergencyContactRepository emergencyContacts,
            MembershipRepository memberships,
            ElderPhotoStorage photoStorage,
            UserRepository users,
            ApplicationEventPublisher events) {
        this.careGroups = careGroups;
        this.elders = elders;
        this.emergencyContacts = emergencyContacts;
        this.memberships = memberships;
        this.photoStorage = photoStorage;
        this.users = users;
        this.events = events;
    }

    /**
     * Cadastra o idoso e, com ele, cria o grupo e a membership administradora do criador
     * (AC-001.2, AC-002.1, D-05 rev. 2) — só é aceito uma vez por conta.
     *
     * @param callerUserId conta que está cadastrando o idoso (vira o Responsável administrador)
     * @param request dados do idoso já validados por Bean Validation
     * @param clock relógio injetado (D-10), repassado a toda fábrica/mutação de entidade
     * @return o idoso recém-criado, pronto para a resposta HTTP
     * @throws GroupAlreadyExistsException a conta já é Responsável de outro grupo (AC-002.1,
     *     D-02) — checagem que {@code x-required-account-state: active-without-group} não faz,
     *     por isso é responsabilidade deste serviço
     */
    @Transactional
    public ElderResponse createElder(UUID callerUserId, ElderRequest request, Clock clock) {
        // 1. "uma vez por conta": x-required-account-state: active-without-group só exige ATIVO,
        //    não olha se já existe grupo — essa parte da regra é deste serviço (AC-002.1, D-02).
        if (memberships.findByUserId(callerUserId).isPresent()) {
            throw new GroupAlreadyExistsException();
        }

        // 2. grupo, idoso, contatos e membership nascem juntos, nesta ordem (D-05 rev. 2).
        CareGroup group = careGroups.save(CareGroup.create(callerUserId, clock));
        Elder elder = elders.save(Elder.create(
                group.getId(),
                request.name(),
                request.birthDate(),
                toEndereco(request.address()),
                request.allergies(),
                request.conditions(),
                clock));
        List<EmergencyContact> contacts = saveContacts(elder.getId(), request.emergencyContacts());
        memberships.save(Membership.admin(group.getId(), callerUserId, clock));

        // 3. monta a resposta a partir do que acabou de ser persistido.
        return ElderResponse.of(elder, contacts);
    }

    /**
     * Devolve o idoso do próprio grupo do chamador (AC-002.3, AC-011.7: qualquer membro ativo,
     * inclusive Cuidador, vê os dados completos, inclusive endereços).
     *
     * @param elderId id pedido na URL
     * @param callerUserId conta autenticada que está pedindo
     * @throws ResourceNotFoundException {@code elderId} não é o idoso do grupo do chamador — ou
     *     porque pertence a outro grupo, ou porque não existe; as duas situações são
     *     indistinguíveis de propósito (D-32, AC-015.3)
     */
    public ElderResponse getElder(UUID elderId, UUID callerUserId) {
        Elder elder = resolveOwnElder(elderId, callerUserId);
        return ElderResponse.of(elder, emergencyContacts.findByElderIdOrderByPosition(elder.getId()));
    }

    /**
     * Substitui todos os dados do idoso do próprio grupo do chamador, inclusive a lista completa
     * de contatos de emergência (AC-002.3, AC-002.9, D-36: {@code UPDATE} simples, sem
     * histórico — a lista de contatos é recriada do zero, não somada à existente).
     *
     * @throws ResourceNotFoundException mesmo critério de {@link #getElder} (D-32, AC-015.3)
     */
    @Transactional
    public ElderResponse updateElder(UUID elderId, UUID callerUserId, ElderRequest request, Clock clock) {
        // 1. resolve e confere posse (mesma regra de GET: outro grupo ou inexistente -> 404).
        Elder elder = resolveOwnElder(elderId, callerUserId);

        // 2. substitui os campos do idoso (sem histórico, D-36).
        elder.update(
                request.name(),
                request.birthDate(),
                toEndereco(request.address()),
                request.allergies(),
                request.conditions(),
                clock);
        elders.save(elder);

        // 3. substitui a lista de contatos por completo: apaga tudo, recria do zero.
        emergencyContacts.deleteByElderId(elder.getId());
        List<EmergencyContact> contacts = saveContacts(elder.getId(), request.emergencyContacts());

        return ElderResponse.of(elder, contacts);
    }

    /**
     * Envia/substitui a única foto do idoso do próprio grupo do chamador (AC-002.6, T-031,
     * D-30): JPEG ou PNG, até 5 MB; uma nova foto sempre substitui a anterior, nunca acumula.
     *
     * @throws ResourceNotFoundException mesmo critério de {@link #getElder} (D-32, AC-015.3)
     * @throws UnsupportedPhotoFormatException {@code Content-Type} fora de {@code image/jpeg}/
     *     {@code image/png}
     * @throws PhotoTooLargeException arquivo maior que 5 MB
     */
    @Transactional
    public ElderResponse uploadPhoto(UUID elderId, UUID callerUserId, MultipartFile file, Clock clock) {
        // 1. resolve e confere posse (mesma regra de GET/PUT).
        Elder elder = resolveOwnElder(elderId, callerUserId);

        // 2. formato: só JPEG/PNG são aceitos (D-30) -> 415 em qualquer outro caso.
        String extension = extensionFor(file.getContentType());

        // 3. tamanho: até 5 MB, borda exata inclusa (D-30) -> 413 acima disso.
        if (file.getSize() > MAX_PHOTO_SIZE_BYTES) {
            throw new PhotoTooLargeException();
        }

        // 4. substitui: apaga o arquivo antigo (se havia) antes de gravar o novo, mesmo trocando
        //    JPEG<->PNG, para não deixar lixo em disco com extensão diferente da atual.
        if (elder.getPhotoPath() != null) {
            photoStorage.delete(elder.getPhotoPath());
        }

        // 5. grava o novo arquivo e atualiza o ponteiro guardado na entidade.
        String photoPath = photoStorage.save(elder.getId(), readBytes(file), extension);
        elder.updatePhoto(photoPath, clock);
        elders.save(elder);

        return ElderResponse.of(elder, emergencyContacts.findByElderIdOrderByPosition(elder.getId()));
    }

    /**
     * Remove a foto do idoso do próprio grupo do chamador (AC-002.6, T-031): idempotente — chamar
     * sem nenhuma foto gravada não é erro, só não faz nada (204 de qualquer forma, decidido pelo
     * controller).
     *
     * @throws ResourceNotFoundException mesmo critério de {@link #getElder} (D-32, AC-015.3)
     */
    @Transactional
    public ElderResponse removePhoto(UUID elderId, UUID callerUserId, Clock clock) {
        Elder elder = resolveOwnElder(elderId, callerUserId);
        if (elder.getPhotoPath() != null) {
            photoStorage.delete(elder.getPhotoPath());
            elder.removePhoto(clock);
            elders.save(elder);
        }
        return ElderResponse.of(elder, emergencyContacts.findByElderIdOrderByPosition(elder.getId()));
    }

    /**
     * Devolve os bytes da foto do idoso do próprio grupo do chamador e o {@code Content-Type}
     * com que servi-los (AC-002.3, AC-002.6, T-031): mesma checagem de posse de {@link
     * #getElder}; "idoso sem foto nenhuma ainda" é reportado com a mesma {@link
     * ResourceNotFoundException} genérica (404), não um motivo diferente.
     *
     * @throws ResourceNotFoundException {@code elderId} de outro grupo/inexistente (D-32,
     *     AC-015.3), ou idoso existente mas sem foto gravada
     */
    public ElderPhotoContent getPhoto(UUID elderId, UUID callerUserId) {
        Elder elder = resolveOwnElder(elderId, callerUserId);
        if (elder.getPhotoPath() == null) {
            throw new ResourceNotFoundException();
        }
        byte[] bytes = photoStorage.read(elder.getPhotoPath());
        MediaType contentType = elder.getPhotoPath().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return new ElderPhotoContent(bytes, contentType);
    }

    /**
     * Exclui o grupo de cuidado inteiro do idoso informado (T-033, AC-013.2, AC-013.3, AC-013.4,
     * AC-013.5, D-38): apagamento físico — idoso, contatos de emergência, memberships (qualquer
     * status) e todas as contas do grupo, inclusive a do Responsável; depois, dispara o e-mail de
     * aviso de AC-013.3 para quem tinha conta ali. Só o Responsável administrador chega até aqui
     * ({@code x-required-permission: admin}, checado pelo controller) — um membro comum nunca
     * exclui a própria conta.
     *
     * @throws ResourceNotFoundException mesmo critério de posse de {@link #getElder} (D-32,
     *     AC-015.3): idoso de outro grupo ou inexistente é 404 genérico
     */
    @Transactional
    public void deleteCareGroup(UUID elderId, UUID callerUserId) {
        // 1. resolve e confere posse (mesma regra de GET/PUT/foto).
        Elder elder = resolveOwnElder(elderId, callerUserId);

        // 2. todas as memberships do grupo, em qualquer status — não só a do chamador.
        List<Membership> groupMemberships = memberships.findByGroupId(elder.getGroupId());

        // 3. coleta o User e o e-mail de cada membership: essa lista alimenta tanto o apagamento
        //    dos users (passo 6) quanto os e-mails de aviso (passo 7).
        List<User> groupUsers = new ArrayList<>(groupMemberships.size());
        List<String> emails = new ArrayList<>(groupMemberships.size());
        for (Membership membership : groupMemberships) {
            User user = users.findById(membership.getUserId()).orElseThrow(ResourceNotFoundException::new);
            groupUsers.add(user);
            emails.add(user.getEmail());
        }

        // 4. apaga o arquivo físico da foto do idoso, se houver (AC-013.2, P5, D-35): o cascade do
        //    banco só cobre linhas, nunca o arquivo em disco apontado por elder.photoPath — sem
        //    este passo, a exclusão do grupo deixaria um arquivo órfão com dado pessoal do idoso
        //    que já não existe em nenhuma linha (mesma chamada de uploadPhoto/removePhoto).
        if (elder.getPhotoPath() != null) {
            photoStorage.delete(elder.getPhotoPath());
        }

        // 5. apaga o grupo: o cascade (D-35) já leva elders, emergency_contacts, memberships e
        //    invitations daquele grupo — não é preciso apagar essas linhas uma a uma aqui.
        CareGroup group = careGroups.findById(elder.getGroupId()).orElseThrow(ResourceNotFoundException::new);
        careGroups.delete(group);

        // 6. apaga as contas (users) coletadas no passo 3 — apagamento físico de conta, não só o
        //    vínculo com o grupo (AC-013.2 "inclusive a do Responsável", AC-013.5).
        users.deleteAll(groupUsers);

        // 7. publica 1 evento por e-mail coletado; o envio real acontece pós-commit (D-23), pelo
        //    mesmo listener usado por SignupService.
        for (String email : emails) {
            events.publishEvent(new GroupDeletedEmail(email));
        }
    }

    /**
     * Resolve "o idoso do chamador" a partir da membership dele (D-02: no máximo uma por conta) e
     * confere que é exatamente o {@code elderId} pedido — a mesma checagem de posse serve tanto
     * para {@code GET} quanto para {@code PUT} (D-32, AC-015.3: outro grupo e inexistente geram o
     * mesmo 404, porque esta exceção não carrega o motivo).
     */
    private Elder resolveOwnElder(UUID elderId, UUID callerUserId) {
        UUID groupId = memberships.findByUserId(callerUserId)
                .map(Membership::getGroupId)
                .orElseThrow(ResourceNotFoundException::new);
        Elder elder = elders.findByGroupId(groupId).orElseThrow(ResourceNotFoundException::new);
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }
        return elder;
    }

    /**
     * Persiste os contatos de emergência na ordem da lista recebida (posição = índice). Usa um
     * laço indexado em vez de {@code stream().indexOf(...)} porque dois contatos com os mesmos
     * valores (registros iguais por {@code equals}) receberiam a mesma posição errada com
     * {@code indexOf}.
     */
    private List<EmergencyContact> saveContacts(UUID elderId, List<EmergencyContactRequest> requests) {
        List<EmergencyContact> saved = new ArrayList<>(requests.size());
        for (int position = 0; position < requests.size(); position++) {
            EmergencyContactRequest c = requests.get(position);
            saved.add(emergencyContacts.save(EmergencyContact.create(
                    elderId, c.name(), c.relationship(), c.phone(), toEndereco(c.address()), position)));
        }
        return saved;
    }

    /**
     * Traduz o {@code Content-Type} declarado no multipart para a extensão de arquivo gravada em
     * disco (D-30) — só {@code image/jpeg}/{@code image/png} são aceitos; qualquer outro valor
     * (inclusive {@code null}, upload sem {@code Content-Type}) é {@code 415}.
     */
    private static String extensionFor(String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)) {
            return ".jpg";
        }
        if (MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)) {
            return ".png";
        }
        throw new UnsupportedPhotoFormatException();
    }

    /**
     * Lê os bytes do arquivo enviado, convertendo a {@link IOException} checada (falha de I/O ao
     * ler o multipart, não um erro de entrada do usuário) numa exceção não checada — propagada
     * para o fallback genérico de 500 do {@code GlobalExceptionHandler}.
     */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo de foto enviado.", e);
        }
    }

    /** Converte o {@code AddressRequest} validado para o objeto de valor de domínio (D-25). */
    private static Endereco toEndereco(AddressRequest address) {
        return new Endereco(
                address.zipCode(),
                address.street(),
                address.number(),
                address.complement(),
                address.district(),
                address.city(),
                address.state());
    }
}
