package turn.turn.floor;

public enum TableState {
    EMPTY, SEATED, BUSSING, READY;

    public boolean isFree() {
        return this == EMPTY || this == READY;
    }
}
