package io.github.nikitaaovramenko.mineai;

// Carries a message that is safe to show a player: never build one from an API response body.
// The provider's own explanation goes in detail instead, which only ever reaches the server log.
final class RequestException extends RuntimeException {
    private final String detail;

    RequestException(String message) {
        this(message, "");
    }

    RequestException(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    String detail() {
        return detail;
    }
}
