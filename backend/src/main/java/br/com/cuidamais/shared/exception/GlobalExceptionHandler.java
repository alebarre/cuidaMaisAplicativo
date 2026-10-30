package br.com.cuidamais.shared.exception;

import br.com.cuidamais.auth.domain.AccountStateValue;
import br.com.cuidamais.auth.exception.AccountNotActiveException;
import br.com.cuidamais.auth.exception.CodeExpiredOrExhaustedException;
import br.com.cuidamais.auth.exception.InvalidCodeException;
import br.com.cuidamais.auth.exception.InvalidCredentialsException;
import br.com.cuidamais.auth.exception.TooManyRequestsException;
import br.com.cuidamais.group.exception.GroupAlreadyExistsException;
import br.com.cuidamais.group.exception.PhotoTooLargeException;
import br.com.cuidamais.group.exception.UnsupportedPhotoFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Tratamento global de erros em Problem Details (RFC 9457, T-007; plan D-32, D-40; Constitution
 * §5/P5).
 *
 * <p>Todo corpo de erro usa {@link ProblemDetail} (suporte nativo do Spring à RFC 9457); campos
 * de extensão (ex. {@code code}, {@code errors}) entram via {@link ProblemDetail#setProperty} e o
 * {@code Jackson2ObjectMapperBuilder} padrão do Spring Boot os serializa achatados no nível raiz
 * do JSON — exatamente a forma do {@code allOf} usado pelo contrato para {@code ValidationProblem}
 * (ver teste de contrato em {@code GlobalExceptionHandlerTest}, que confere isso no JSON real).
 *
 * <p><strong>Nunca</strong> inclui stack trace, mensagem bruta de exceção, valor rejeitado de
 * campo ou qualquer outro dado potencialmente sensível no corpo da resposta (P5) — só textos
 * genéricos de desenvolvedor em {@code title}/{@code detail} e um {@code code} estável. O texto
 * exato exibido ao usuário é responsabilidade do catálogo de mensagens do frontend (D-40, tarefa
 * futura).
 *
 * <p>{@link ResourceNotFoundException} cobre ao mesmo tempo "recurso de outro grupo" e "recurso
 * inexistente" (D-32, AC-015.3): como nem a exceção nem este handler guardam qual dos dois
 * motivos ocorreu, o corpo produzido aqui é idêntico nos dois casos — só {@code instance} (o path
 * da requisição) varia naturalmente entre chamadas.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI VALIDATION_ERROR_TYPE = URI.create("/problems/validation-error");
    private static final URI NOT_ALLOWED_TYPE = URI.create("/problems/not-allowed");
    private static final URI NOT_FOUND_TYPE = URI.create("/problems/not-found");
    private static final URI INTERNAL_ERROR_TYPE = URI.create("/problems/internal-error");
    private static final URI INVALID_CODE_TYPE = URI.create("/problems/invalid-code");
    private static final URI CODE_EXPIRED_TYPE = URI.create("/problems/code-expired-or-exhausted");
    private static final URI INVALID_CREDENTIALS_TYPE = URI.create("/problems/invalid-credentials");
    private static final URI ACCOUNT_NOT_ACTIVE_TYPE = URI.create("/problems/account-not-active");
    private static final URI UNAUTHORIZED_TYPE = URI.create("/problems/unauthorized");
    private static final URI ACCOUNT_STATE_TYPE = URI.create("/problems/account-state");
    private static final URI TOO_MANY_REQUESTS_TYPE = URI.create("/problems/too-many-requests");
    private static final URI GROUP_ALREADY_EXISTS_TYPE = URI.create("/problems/group-already-exists");
    private static final URI PHOTO_TOO_LARGE_TYPE = URI.create("/problems/photo-too-large");
    private static final URI UNSUPPORTED_PHOTO_FORMAT_TYPE =
            URI.create("/problems/unsupported-photo-format");

    /** AC-015.3 / D-32: validação de {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldErrorOf)
                .toList();
        return validationProblem(errors, request);
    }

    /** Validação de parâmetros de método (ex. {@code @RequestParam @NotBlank}). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getParameterValidationResults().stream()
                .map(result -> {
                    String field = result.getMethodParameter().getParameterName();
                    String message = result.getResolvableErrors().stream()
                            .findFirst()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .orElse("Valor inválido.");
                    return fieldError(field == null ? "parâmetro" : field, message);
                })
                .toList();
        return validationProblem(errors, request);
    }

    /**
     * T-030, AC-002.7: corpo que não desserializa (JSON malformado, enum fora do domínio ou —
     * o caso real que motivou este handler — uma data sintaticamente ISO mas inexistente no
     * calendário, ex. {@code "2024-02-30"}, que o Jackson recusa antes de qualquer validação de
     * Bean Validation rodar). Sem este handler a exceção cairia no fallback genérico de {@code
     * Exception} e viraria 500, violando a regra geral do projeto de nunca deixar erro de entrada
     * do usuário se tornar erro interno (P5). Não há como extrair com segurança qual campo exato
     * falhou a partir desta exceção, então o erro é reportado de forma genérica (campo {@code
     * "body"}) — ainda assim um 400 com {@code ValidationProblem}, nunca um 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformedBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        List<Map<String, String>> errors =
                List.of(fieldError("body", "Corpo da requisição mal formado ou com data inválida."));
        return validationProblem(errors, request);
    }

    /** Validação de parâmetros fora de controllers (ex. serviço com {@code @Validated}). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::fieldErrorOf)
                .toList();
        return validationProblem(errors, request);
    }

    /** D-32, AC-015.2: ação sobre recurso do próprio grupo vedada ao papel. */
    @ExceptionHandler(NotAllowedException.class)
    public ResponseEntity<ProblemDetail> handleNotAllowed(
            NotAllowedException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Esta ação não é permitida para o papel do usuário.");
        problem.setType(NOT_ALLOWED_TYPE);
        problem.setTitle("Ação não permitida");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "NOT_ALLOWED");
        return problemResponse(HttpStatus.FORBIDDEN, problem);
    }

    /** D-32, AC-015.3: recurso de outro grupo ou inexistente — corpo idêntico nos dois casos. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "O recurso solicitado não foi encontrado.");
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Recurso não encontrado");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.NOT_FOUND, problem);
    }

    /**
     * T-030, AC-002.1/D-02: a conta que chamou {@code POST /elders} já é Responsável de um
     * grupo. {@code Problem} genérico, sem {@code code} (mesmo padrão de {@link
     * ResourceNotFoundException}) — o motivo já é evidente pelo contexto da própria chamada.
     */
    @ExceptionHandler(GroupAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleGroupAlreadyExists(
            GroupAlreadyExistsException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "A conta já pertence a um grupo de cuidado.");
        problem.setType(GROUP_ALREADY_EXISTS_TYPE);
        problem.setTitle("Grupo já existe");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.CONFLICT, problem);
    }

    /**
     * T-031, AC-002.6/D-30: upload de foto do idoso ({@code PUT /elders/{id}/photo}) maior que o
     * limite de 5 MB decidido pela regra de negócio ({@code ElderService}). {@code Problem}
     * genérico, sem {@code code} (mesmo padrão de {@link GroupAlreadyExistsException}).
     */
    @ExceptionHandler(PhotoTooLargeException.class)
    public ResponseEntity<ProblemDetail> handlePhotoTooLarge(
            PhotoTooLargeException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo enviado é maior que o limite permitido.");
        problem.setType(PHOTO_TOO_LARGE_TYPE);
        problem.setTitle("Arquivo muito grande");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.PAYLOAD_TOO_LARGE, problem);
    }

    /**
     * T-031, AC-002.6/D-30: upload de foto do idoso com {@code Content-Type} diferente de
     * {@code image/jpeg}/{@code image/png}. {@code Problem} genérico, sem {@code code}.
     */
    @ExceptionHandler(UnsupportedPhotoFormatException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedPhotoFormat(
            UnsupportedPhotoFormatException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Envie um arquivo JPEG ou PNG.");
        problem.setType(UNSUPPORTED_PHOTO_FORMAT_TYPE);
        problem.setTitle("Formato não suportado");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, problem);
    }

    /**
     * T-031, D-30: rede de segurança para um upload absurdamente grande que nem o limite do
     * Spring ({@code spring.servlet.multipart.max-file-size}, maior que 5 MB de propósito —
     * application.yml) deixaria passar, antes mesmo de {@code ElderService} decidir o 413 de
     * negócio. Sem este handler, {@code MaxUploadSizeExceededException} cairia no fallback
     * genérico de {@link Exception} e viraria {@code 500} em vez do {@code 413} que o contrato
     * documenta.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo enviado é maior que o limite permitido.");
        problem.setType(PHOTO_TOO_LARGE_TYPE);
        problem.setTitle("Arquivo muito grande");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.PAYLOAD_TOO_LARGE, problem);
    }

    /**
     * T-022, AC-005.2: código de confirmação de formato válido mas diferente do esperado.
     * {@code remainingAttempts} (quantas tentativas erradas ainda restam) vai no corpo como
     * {@code InvalidCodeProblem} do contrato — nunca o código certo nem qualquer outro dado da
     * conta (P5).
     */
    @ExceptionHandler(InvalidCodeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCode(
            InvalidCodeException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "O código informado está incorreto.");
        problem.setType(INVALID_CODE_TYPE);
        problem.setTitle("Código incorreto");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INVALID_CODE");
        problem.setProperty("remainingAttempts", exception.getRemainingAttempts());
        return problemResponse(HttpStatus.BAD_REQUEST, problem);
    }

    /**
     * T-022, AC-005.1/AC-005.2: "nada a confirmar agora" — código expirado, já consumido, com as
     * tentativas esgotadas, conta sem código pendente, ou e-mail sem conta nenhuma. {@code
     * Problem} genérico, sem {@code remainingAttempts} (P5: os cinco motivos são indistinguíveis
     * na resposta, para nunca revelar se o e-mail informado tem cadastro).
     */
    @ExceptionHandler(CodeExpiredOrExhaustedException.class)
    public ResponseEntity<ProblemDetail> handleCodeExpiredOrExhausted(
            CodeExpiredOrExhaustedException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE, "Código expirado ou esgotado; peça um código novo.");
        problem.setType(CODE_EXPIRED_TYPE);
        problem.setTitle("Código expirado ou esgotado");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "CODE_EXPIRED_OR_EXHAUSTED");
        return problemResponse(HttpStatus.GONE, problem);
    }

    /**
     * T-023, AC-008.4, D-15: e-mail inexistente, senha errada ou conta bloqueada por 5 erros
     * seguidos — os três motivos respondem com o mesmo {@code Problem} genérico (P5), sem
     * nenhum campo de extensão e sem nenhum dado da conta no corpo, para nunca revelar se um
     * e-mail tem cadastro ou se a conta informada está bloqueada.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
        problem.setType(INVALID_CREDENTIALS_TYPE);
        problem.setTitle("Credenciais inválidas");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.UNAUTHORIZED, problem);
    }

    /**
     * T-023, AC-008.2, D-33: e-mail e senha corretos, mas a conta não está {@code ATIVO}. O
     * {@code code} segue a tabela do contrato ({@code AccountStateProblem}) e só é revelado
     * depois da senha confirmada — nunca para quem apenas tenta e-mails ao acaso.
     */
    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotActive(
            AccountNotActiveException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "A conta não está ativa.");
        problem.setType(ACCOUNT_NOT_ACTIVE_TYPE);
        problem.setTitle("Conta não ativa");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", accountStateCodeOf(exception.getState()));
        return problemResponse(HttpStatus.FORBIDDEN, problem);
    }

    /**
     * T-024, D-13, AC-009.2: nenhuma sessão válida para a rota autenticada chamada — token
     * ausente, inválido, expirado, de uma conta que não existe mais, ou de uma membership que
     * virou {@code REMOVIDO}. {@code Problem} genérico, sem {@code code} e sem nenhum dado da
     * conta (P5): quem não tem sessão válida não deve saber qual dos motivos ocorreu.
     */
    @ExceptionHandler(UnauthorizedSessionException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorizedSession(
            UnauthorizedSessionException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada.");
        problem.setType(UNAUTHORIZED_TYPE);
        problem.setTitle("Sessão inválida");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.UNAUTHORIZED, problem);
    }

    /**
     * T-024, AC-009.1: sessão válida, mas o estado atual da conta não satisfaz a exigência da
     * rota (ex. {@code ATIVO} sem grupo numa rota que exige grupo) — {@code code: ACCOUNT_STATE},
     * sem revelar qual é o estado de verdade (P5).
     */
    @ExceptionHandler(AccountStateException.class)
    public ResponseEntity<ProblemDetail> handleAccountState(
            AccountStateException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "O estado atual da conta não permite esta operação.");
        problem.setType(ACCOUNT_STATE_TYPE);
        problem.setTitle("Estado da conta não permitido");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "ACCOUNT_STATE");
        return problemResponse(HttpStatus.FORBIDDEN, problem);
    }

    /**
     * T-026, AC-005.3: reenvio de código ou pedido de redefinição de senha chegou antes do
     * intervalo mínimo de 1 min desde o último envio. O cabeçalho HTTP {@code Retry-After} (não um
     * campo do corpo — contrato, {@code components.responses.TooManyRequests.headers.Retry-After})
     * informa quantos segundos faltam para o próximo pedido ser aceito; o corpo é um {@code
     * Problem} genérico, sem nenhum dado da conta (P5).
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyRequests(
            TooManyRequestsException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Aguarde antes de pedir um novo código.");
        problem.setType(TOO_MANY_REQUESTS_TYPE);
        problem.setTitle("Muitas requisições");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "TOO_MANY_REQUESTS");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Tabela fixa do contrato ({@code AccountStateProblem.code}, AC-008.2): cada estado não-{@code
     * ATIVO} da conta vira um código estável para o frontend decidir a mensagem/ação. {@code
     * ATIVO} nunca chega aqui (sempre abre sessão, 200) — ver {@code LoginService#login}.
     */
    private static String accountStateCodeOf(AccountStateValue state) {
        return switch (state) {
            case AGUARDANDO_CONFIRMACAO_EMAIL -> "AWAITING_EMAIL_CONFIRMATION";
            case AGUARDANDO_APROVACAO -> "AWAITING_APPROVAL";
            case RECUSADO -> "REJECTED";
            case EXPIRADO -> "EXPIRED";
            case REMOVIDO -> "REMOVED";
            case ATIVO ->
                    throw new IllegalStateException(
                            "ATIVO nunca gera AccountNotActiveException (AC-008.2)");
        };
    }

    /**
     * Fallback para qualquer exceção não tratada: garante que nenhum stack trace ou mensagem
     * crua escape em produção (P5), mesmo para erros imprevistos.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Ocorreu um erro inesperado.");
        problem.setType(INTERNAL_ERROR_TYPE);
        problem.setTitle("Erro interno");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR, problem);
    }

    private ResponseEntity<ProblemDetail> validationProblem(
            List<Map<String, String>> errors, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "A requisição contém campos inválidos.");
        problem.setType(VALIDATION_ERROR_TYPE);
        problem.setTitle("Dados inválidos");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);
        return problemResponse(HttpStatus.BAD_REQUEST, problem);
    }

    private ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, ProblemDetail body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static Map<String, String> fieldErrorOf(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return fieldError(fieldError.getField(), message == null ? "Valor inválido." : message);
    }

    private static Map<String, String> fieldErrorOf(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath().toString();
        return fieldError(field.isBlank() ? "parâmetro" : field, violation.getMessage());
    }

    private static Map<String, String> fieldError(String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}
