package br.com.cuidamais.group;

import br.com.cuidamais.auth.AddressRequest;
import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.ResourceNotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra o cadastro, a leitura e a edição do idoso de um grupo (T-030, D-05 rev. 2, D-07: a
 * regra de negócio mora nas entidades — {@link CareGroup}, {@link Elder}, {@link
 * br.com.cuidamais.membership.Membership} — este serviço só decide <strong>quando</strong>
 * chamá-las e persiste o resultado).
 *
 * <p>Todo método é {@code @Transactional}: {@link #createElder} precisa que grupo, idoso,
 * contatos e membership nasçam juntos ou nada nasça (AC-001.2, D-05); {@link #updateElder}
 * precisa que a substituição completa dos contatos de emergência (apagar todos, recriar a lista
 * nova) seja atômica (AC-002.3, AC-002.9).
 */
@Service
public class ElderService {

    private final CareGroupRepository careGroups;
    private final ElderRepository elders;
    private final EmergencyContactRepository emergencyContacts;
    private final MembershipRepository memberships;

    public ElderService(
            CareGroupRepository careGroups,
            ElderRepository elders,
            EmergencyContactRepository emergencyContacts,
            MembershipRepository memberships) {
        this.careGroups = careGroups;
        this.elders = elders;
        this.emergencyContacts = emergencyContacts;
        this.memberships = memberships;
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
