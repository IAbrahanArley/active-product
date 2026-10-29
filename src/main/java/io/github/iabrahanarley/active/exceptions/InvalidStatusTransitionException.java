package io.github.iabrahanarley.active.exceptions;

import io.github.iabrahanarley.active.enums.ActivationStatus;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(ActivationStatus current, ActivationStatus target) {
        super("Transição de " + current + " para " + target + " não permitida.");
    }
}
