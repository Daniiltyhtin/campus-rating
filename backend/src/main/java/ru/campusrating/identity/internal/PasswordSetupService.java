package ru.campusrating.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.campusrating.shared.error.*;
import ru.campusrating.shared.mail.MailModeProperties;

@Service
public class PasswordSetupService {
    private static final Logger LOG=LoggerFactory.getLogger(PasswordSetupService.class);
    private final JdbcClient jdbc;private final Passwords passwords;private final Clock clock;private final Environment environment;
    private final ObjectProvider<JavaMailSender> senders;private final MailModeProperties mode;private final SessionRegistry sessions;
    private final SecureRandom random=new SecureRandom();
    public PasswordSetupService(JdbcClient jdbc,Passwords passwords,Clock clock,Environment environment,ObjectProvider<JavaMailSender> senders,MailModeProperties mode,SessionRegistry sessions) {
        this.jdbc=jdbc;this.passwords=passwords;this.clock=clock;this.environment=environment;this.senders=senders;this.mode=mode;this.sessions=sessions;
    }
    public boolean required() {return mode.emailVerification()==MailModeProperties.EmailVerification.REQUIRED;}
    public boolean verified(long id) {return jdbc.sql("select email_verified from app_user where id=:id").param("id",id).query(Boolean.class).optional().orElse(false);}
    public void sendSetup(long id,String email) {if(smtp()) issue(id,email,"PASSWORD_SETUP","/password/set/","Установка пароля");}
    public void sendVerification(long id,String email) {if(required()) issue(id,email,"EMAIL_VERIFICATION","/email/confirm/","Подтверждение email");}
    @Transactional public void resend(String email) {
        if(!required()) return;
        jdbc.sql("select id,email from app_user where lower(email)=lower(:email) and email_verified=false").param("email",email.strip())
                .query((rs,n)->new Account(rs.getLong(1),rs.getString(2))).optional().ifPresent(u->sendVerification(u.id(),u.email()));
    }
    private record Account(long id,String email) {}
    private boolean smtp() {String host=environment.getProperty("spring.mail.host");return host!=null&&!host.isBlank();}
    private void issue(long id,String email,String purpose,String route,String subject) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into password_setup_token(token_hash,user_id,purpose,expires_at) values(:hash,:user,:purpose,:expires)")
                .param("hash",hash(token)).param("user",id).param("purpose",purpose).param("expires",Timestamp.from(clock.instant().plus(Duration.ofHours(24)))).update();
        var message=new SimpleMailMessage();message.setTo(email);message.setSubject(subject);
        String from=environment.getProperty("campus.mail.from",environment.getProperty("spring.mail.username",""));if(!from.isBlank()) message.setFrom(from);
        message.setText(subject+": "+environment.getProperty("campus.public-url","http://localhost:8080").replaceAll("/$","")+route+token+"\nСсылка действует 24 часа.");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {var sender=senders.getIfAvailable();if(sender!=null) sender.send(message);else LOG.warn("Mail sender unavailable for user {}",id);}
                catch(Exception e) {LOG.warn("Mail delivery failed for user {}",id);}
            }
        });
    }
    @Transactional public void setPassword(String token,String password) {consume(token,"PASSWORD_SETUP",password);}
    @Transactional public void confirm(String token) {consume(token,"EMAIL_VERIFICATION",null);}
    private void consume(String token,String purpose,String password) {
        String digest=hash(token);
        long id=jdbc.sql("select user_id from password_setup_token where token_hash=:hash and purpose=:purpose")
                .param("hash",digest).param("purpose",purpose).query(Long.class).optional().orElseThrow(this::used);
        jdbc.sql("select id from app_user where id=:id for update").param("id",id).query(Long.class).optional().orElseThrow(this::used);
        var state=jdbc.sql("select expires_at,consumed_at from password_setup_token where token_hash=:hash for update")
                .param("hash",digest).query((rs,n)->new TokenState(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2)!=null)).single();
        if(state.consumed()) throw used();
        if(!clock.instant().isBefore(state.expires())) throw new ApiException(ProblemCode.INVALID_STATE,"Ссылка устарела. Попросите преподавателя или администратора выставить временный пароль");
        if(password!=null) jdbc.sql("update app_user set password_hash=:hash,must_change_password=false where id=:id")
                .param("hash",passwords.hash(password,"password")).param("id",id).update();
        jdbc.sql("update app_user set email_verified=true where id=:id").param("id",id).update();
        jdbc.sql("update password_setup_token set consumed_at=:now where token_hash=:hash").param("now",Timestamp.from(clock.instant())).param("hash",digest).update();
        if(required()) autoApprove(id);
        if(password!=null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {for(var principal:sessions.getAllPrincipals()) if(principal instanceof IdentityPrincipal p&&p.userId()==id) sessions.getAllSessions(principal,false).forEach(s->s.expireNow());}
        });
    }
    private record TokenState(java.time.Instant expires,boolean consumed) {}
    private void autoApprove(long id) {
        var department=jdbc.sql("select a.department_id from teacher_allowlist a join app_user u on lower(a.email)=lower(u.email) where u.id=:id and u.role='TEACHER'")
                .param("id",id).query(Long.class).optional();
        if(department.isEmpty()) return;
        jdbc.sql("update app_user set department_id=:department where id=:id").param("department",department.get()).param("id",id).update();
        jdbc.sql("insert into teacher_approval(teacher_id,approved_by,approved_at) values(:id,null,:now) on conflict do nothing")
                .param("id",id).param("now",Timestamp.from(clock.instant())).update();
    }
    private ApiException used() {return new ApiException(ProblemCode.INVALID_STATE,"Ссылка уже использована");}
    private static String hash(String token) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
