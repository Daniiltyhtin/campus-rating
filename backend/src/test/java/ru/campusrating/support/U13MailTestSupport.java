package ru.campusrating.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@TestPropertySource(properties={"spring.mail.host=smtp.example.test","campus.public-url=https://campus.example.test","campus.mail.from=sender@example.test","management.health.mail.enabled=false"})
public abstract class U13MailTestSupport extends U13HttpTestSupport {
    @MockitoBean protected JavaMailSender sender;
    protected final List<SimpleMailMessage> messages=new CopyOnWriteArrayList<>();
    @BeforeEach void captureMail() {
        messages.clear();
        doAnswer(call -> {
            var message=call.getArgument(0,SimpleMailMessage.class);
            try(var connection=jdbc.getDataSource().getConnection(); var statement=connection.prepareStatement("select count(*) from app_user where email=?")) {
                statement.setString(1,message.getTo()[0]);
                try(var result=statement.executeQuery()) { result.next(); assertThat(result.getInt(1)).as("Email is sent only after recipient commit").isEqualTo(1); }
            }
            messages.add(new SimpleMailMessage(message)); return null;
        })
                .when(sender).send(any(SimpleMailMessage.class));
    }
    protected String token(String email,String route) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(messages.stream().filter(m->m.getTo()!=null&&List.of(m.getTo()).contains(email))).isNotEmpty());
        String body=messages.stream().filter(m->m.getTo()!=null&&List.of(m.getTo()).contains(email)).reduce((a,b)->b).orElseThrow().getText();
        assertThat(body).contains("https://campus.example.test"+route);
        var match=Pattern.compile(Pattern.quote(route)+"([A-Za-z0-9_-]+)").matcher(body);
        assertThat(match.find()).withFailMessage("Expected %s token in message",route).isTrue();
        return match.group(1);
    }
    protected long imported(String suffix) {
        String data="email,full_name,faculty_code,group_code\n"+email(suffix)+",Импортированный,"+prefix+"f1,"+prefix+"g1\n";
        var result=adminWire().upload("/api/v1/admin/import/STUDENTS","students.csv",data.getBytes(java.nio.charset.StandardCharsets.UTF_8),200);
        assertThat(result.path("created").asInt()).isEqualTo(1);
        return items(get("/api/v1/admin/users?search="+prefix+suffix)).getFirst().path("id").asLong();
    }
}
