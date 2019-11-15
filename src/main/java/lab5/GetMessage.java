package lab5;

public class GetMessage {
    private String site;
    private Integer requestCount;

    public GetMessage(String site, int requestCount) {
        this.site = site;
        this.requestCount = requestCount;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public String getSite() {
        return site;
    }
}

