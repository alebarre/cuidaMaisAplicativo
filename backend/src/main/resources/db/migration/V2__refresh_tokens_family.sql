-- T-021 (D-11 rev. 3): identifica a cadeia de rotação de um refresh token. O primeiro token de
-- um login nasce com family_id = id (ele mesmo); cada renovação grava um token novo com o mesmo
-- family_id do anterior. Detectar um token já revogado sendo reapresentado revoga todas as
-- linhas daquele family_id (reuso = token roubado). Nunca é o user_id: revogar um aparelho
-- (logout) não deve afetar os refresh tokens de outros aparelhos do mesmo usuário.

ALTER TABLE refresh_tokens ADD COLUMN family_id UUID NULL;
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
