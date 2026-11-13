package ru.campusrating.poll.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.poll.domain.PollQuestionType;

@Repository
class PollRepository {
    record PollRow(long id, long authorId, String title, String description, Instant endsAt, boolean anonymous) {}

    record QuestionRow(long id, String text, PollQuestionType type) {}

    record OptionRow(long id, long questionId, String text, int votes) {}

    record VotingState(Instant endsAt, boolean closed) {}

    record OptionResultRow(long id, long questionId, int votes, int voted) {}

    record GroupLink(long pollId, long groupId) {}

    record Choice(long studentId, long optionId) {}

    private static final String POLL_COLUMNS = "p.id,p.author_id,p.title,p.description,p.ends_at,p.anonymous";

    private final JdbcClient jdbc;

    PollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(long author, String title, String description, Instant endsAt, boolean anonymous, Instant now) {
        return jdbc.sql("""
                insert into poll(author_id,title,description,ends_at,anonymous,created_at)
                values (:author,:title,:description,:ends,:anonymous,:now) returning id
                """)
                .param("author", author).param("title", title).param("description", description)
                .param("ends", Timestamp.from(endsAt)).param("anonymous", anonymous).param("now", Timestamp.from(now))
                .query(Long.class).single();
    }

    void addGroup(long poll, long group) {
        jdbc.sql("insert into poll_group(poll_id,group_id) values (:poll,:group)").param("poll", poll).param("group", group).update();
    }

    long addQuestion(long poll, int position, String text, PollQuestionType type) {
        return jdbc.sql("insert into poll_question(poll_id,position,text,type) values (:poll,:position,:text,:type) returning id")
                .param("poll", poll).param("position", position).param("text", text).param("type", type.name())
                .query(Long.class).single();
    }

    void addOption(long question, int position, String text) {
        jdbc.sql("insert into poll_option(question_id,position,text) values (:question,:position,:text)")
                .param("question", question).param("position", position).param("text", text).update();
    }

    Optional<PollRow> find(long poll) {
        return jdbc.sql("select " + POLL_COLUMNS + " from poll p where p.id=:poll").param("poll", poll)
                .query(PollRepository::poll).optional();
    }

    VotingState lockForVote(long poll) {
        return jdbc.sql("select ends_at,closed_at from poll where id=:poll for update").param("poll", poll)
                .query((rs, row) -> new VotingState(rs.getTimestamp("ends_at").toInstant(),
                        rs.getTimestamp("closed_at") != null)).single();
    }

    List<PollRow> byAuthor(long author) {
        return jdbc.sql("select " + POLL_COLUMNS + " from poll p where p.author_id=:author").param("author", author)
                .query(PollRepository::poll).list();
    }

    List<PollRow> byGroup(long group) {
        return jdbc.sql("select " + POLL_COLUMNS + " from poll p join poll_group g on g.poll_id=p.id where g.group_id=:group")
                .param("group", group).query(PollRepository::poll).list();
    }

    List<GroupLink> groups(Collection<Long> polls) {
        if (polls.isEmpty()) return List.of();
        return jdbc.sql("select poll_id,group_id from poll_group where poll_id in (:polls) order by poll_id,group_id")
                .param("polls", polls)
                .query((rs, row) -> new GroupLink(rs.getLong("poll_id"), rs.getLong("group_id"))).list();
    }

    List<QuestionRow> questions(long poll) {
        return jdbc.sql("select id,text,type from poll_question where poll_id=:poll order by position").param("poll", poll)
                .query((rs, row) -> new QuestionRow(rs.getLong("id"), rs.getString("text"),
                        PollQuestionType.valueOf(rs.getString("type")))).list();
    }

    List<OptionRow> options(long poll) {
        return jdbc.sql("""
                select o.id,o.question_id,o.text,o.vote_count from poll_option o join poll_question q on q.id=o.question_id
                where q.poll_id=:poll order by q.position,o.position
                """).param("poll", poll)
                .query((rs, row) -> new OptionRow(rs.getLong("id"), rs.getLong("question_id"), rs.getString("text"),
                        rs.getInt("vote_count"))).list();
    }

    List<OptionResultRow> results(long poll) {
        return jdbc.sql("""
                select o.id,o.question_id,o.vote_count,
                    (select count(*) from poll_participation where poll_id=:poll) as voted
                from poll_option o join poll_question q on q.id=o.question_id
                where q.poll_id=:poll order by q.position,o.position
                """).param("poll", poll)
                .query((rs, row) -> new OptionResultRow(rs.getLong("id"), rs.getLong("question_id"),
                        rs.getInt("vote_count"), rs.getInt("voted"))).list();
    }

    int participants(long poll) {
        return jdbc.sql("select count(*) from poll_participation where poll_id=:poll").param("poll", poll)
                .query(Integer.class).single();
    }

    List<Long> votedPolls(long student, Collection<Long> polls) {
        if (polls.isEmpty()) return List.of();
        return jdbc.sql("select poll_id from poll_participation where student_id=:student and poll_id in (:polls)")
                .param("student", student).param("polls", polls).query(Long.class).list();
    }

    boolean participate(long poll, long student, Instant now) {
        return jdbc.sql("""
                insert into poll_participation(poll_id,student_id,voted_at) values (:poll,:student,:now)
                on conflict (poll_id,student_id) do nothing
                """).param("poll", poll).param("student", student).param("now", Timestamp.from(now)).update() == 1;
    }

    void count(Collection<Long> options) {
        jdbc.sql("update poll_option set vote_count=vote_count+1 where id in (:options)").param("options", options).update();
    }

    void name(long poll, long student, Collection<Long> options) {
        for (long option : options) {
            jdbc.sql("insert into poll_named_choice(poll_id,option_id,student_id) values (:poll,:option,:student)")
                    .param("poll", poll).param("option", option).param("student", student).update();
        }
    }

    List<Choice> namedChoices(long poll) {
        return jdbc.sql("select student_id,option_id from poll_named_choice where poll_id=:poll").param("poll", poll)
                .query((rs, row) -> new Choice(rs.getLong("student_id"), rs.getLong("option_id"))).list();
    }

    List<PollRow> lockDue(Instant now) {
        return jdbc.sql("select " + POLL_COLUMNS + " from poll p where p.closed_at is null and p.ends_at<=:now order by p.id for update skip locked")
                .param("now", Timestamp.from(now)).query(PollRepository::poll).list();
    }

    void close(long poll, Instant now) {
        jdbc.sql("update poll set closed_at=:now where id=:poll").param("poll", poll).param("now", Timestamp.from(now)).update();
    }

    private static PollRow poll(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PollRow(rs.getLong("id"), rs.getLong("author_id"), rs.getString("title"), rs.getString("description"),
                rs.getTimestamp("ends_at").toInstant(), rs.getBoolean("anonymous"));
    }
}
