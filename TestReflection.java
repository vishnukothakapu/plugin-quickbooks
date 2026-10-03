import io.kestra.core.http.HttpRequest;
import java.lang.reflect.Field;
public class TestReflection {
    public static void main(String[] args) throws Exception {
        HttpRequest req = HttpRequest.builder().uri(java.net.URI.create(" http://test\)).method(\GET\).build();
