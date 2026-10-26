package turn.turn.floor;

public enum AlertType {
    NOT_GREETED("Not greeted"),
    CHECK_IN("Check in"),
    HELP("Help requested"),
    CHECK("Waiting on check"),
    BUSSING("Needs bussing");

    public final String label;

    AlertType(String label) {
        this.label = label;
    }
}
