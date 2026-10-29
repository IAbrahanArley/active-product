package io.github.iabrahanarley.active.exceptions;

public class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException() {
        super("Já existe uma solicitação com este externalId.");
    }
}
