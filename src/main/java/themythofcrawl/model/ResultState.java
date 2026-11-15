package themythofcrawl.model;

/** State of one row (the original or a role) of an API. */
public final class ResultState {
    public static final String ORIGIN = "GỐC (Proxy)";
    public static final String NOT_SENT = "Not sent";
    public static final String QUEUED = "Queued";
    public static final String RETRY = "Chờ thử lại";
    public static final String SENDING = "Sending";
    public static final String DONE = "Done";
    public static final String ERROR = "Error";

    private ResultState() {
    }

    public static boolean finished(String s) {
        return DONE.equals(s) || ERROR.equals(s);
    }
}
