ALTER TABLE drawing_public_share
    ADD COLUMN token_ciphertext VARBINARY(96) NULL,
    ADD COLUMN token_nonce BINARY(12) NULL;
