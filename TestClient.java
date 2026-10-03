import io.kestra.core.http.HttpRequest;
public class TestClient {
    public static void check(HttpRequest req) {
        req.getHeaders();
        req.getUri();
        req.getMethod();
        req.getBody();
        HttpRequest.builder().headers(req.getHeaders());
    }
}
