package ru.campusrating.support;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class U13HttpTestSupport extends AdminHttpTestSupport {
    @LocalServerPort private int serverPort;
    @org.junit.jupiter.api.AfterEach void cleanupU13Records() {
        if (jdbc.queryForObject("select to_regclass('password_setup_token') is not null",Boolean.class))
            jdbc.update("delete from password_setup_token where user_id in (select id from app_user where email like ?)",prefix+"%");
        if (jdbc.queryForObject("select to_regclass('temporary_password_audit') is not null",Boolean.class))
            jdbc.update("delete from temporary_password_audit where user_id in (select id from app_user where email like ?) or actor_id in (select id from app_user where email like ?)",prefix+"%",prefix+"%");
    }
    protected Wire wire(String suffix, String password) {
        var wire = new Wire();
        wire.json("/api/v1/auth/login", Map.of("email",email(suffix),"password",password),200);
        return wire;
    }
    protected Wire adminWire() { return wire("admin","Changed-password-43"); }
    protected final class Wire {
        private final JsonMapper json = new JsonMapper();
        private final CookieManager cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final RestTestClient client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(HttpClient.newBuilder().cookieHandler(cookies).build()))
                .baseUrl("http://127.0.0.1:"+serverPort).build();
        private String csrf() {
            client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(c -> URLDecoder.decode(c.getValue(),StandardCharsets.UTF_8)).findFirst().orElseThrow();
        }
        public JsonNode json(String path,Map<String,?> body,int status) {
            return result(client.post().uri(path).contentType(MediaType.APPLICATION_JSON).header("X-XSRF-TOKEN",csrf())
                    .body(json.writeValueAsString(body)).exchange().returnResult(String.class),status);
        }
        public JsonNode upload(String path,String name,byte[] bytes,int status) {
            return multipart(path,"file",name,bytes,status);
        }
        public JsonNode uploadFailure(String path,String name,byte[] bytes) {
            return multipart(path,"file",name,bytes,-1);
        }
        public JsonNode link(String path,String link,int status) {
            return multipart(path,"link",null,link.getBytes(StandardCharsets.UTF_8),status);
        }
        private JsonNode multipart(String path,String field,String name,byte[] bytes,int status) {
            String boundary="u13-"+UUID.randomUUID();
            var data=new ByteArrayOutputStream();
            data.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\""+(name==null?"":"; filename=\""+name+"\"")+"\r\n"+(name==null?"":"Content-Type: "+(name.endsWith(".pdf")?"application/pdf":"application/octet-stream")+"\r\n")+"\r\n").getBytes(StandardCharsets.UTF_8));
            data.writeBytes(bytes);
            data.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            return result(client.post().uri(path).header("X-XSRF-TOKEN",csrf()).contentType(MediaType.parseMediaType("multipart/form-data; boundary="+boundary))
                    .body(data.toByteArray()).exchange().returnResult(String.class),status);
        }
        public byte[] download(String path,String filename) {
            var response=client.get().uri(path).exchange().expectStatus().isOk().returnResult(byte[].class);
            assertThat(response.getResponseHeaders().getContentDisposition().getFilename()).isEqualTo(filename);
            return response.getResponseBody();
        }
        private JsonNode result(org.springframework.test.web.servlet.client.EntityExchangeResult<String> response,int status) {
            if(status<0) assertThat(response.getStatus().value()).isBetween(400,599);
            else assertThat(response.getStatus().value()).withFailMessage("HTTP %s: %s",response.getStatus(),response.getResponseBody()).isEqualTo(status);
            String text=response.getResponseBody();
            return text==null||text.isBlank()?json.createObjectNode():json.readTree(text);
        }
    }
}
