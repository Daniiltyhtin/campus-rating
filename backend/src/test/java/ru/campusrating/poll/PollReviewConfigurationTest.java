package ru.campusrating.poll;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

import static org.assertj.core.api.Assertions.assertThat;

class PollReviewConfigurationTest {
    @Test
    void qualityIncludesPollMutationGate() throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        Node execution = (Node) xpath.evaluate("//profile[id='quality']//execution[id='pit-poll']", document,
                javax.xml.xpath.XPathConstants.NODE);
        assertThat(execution).isNotNull();
        assertThat(xpath.evaluate("configuration/targetClasses/param", execution)).isEqualTo("ru.campusrating.poll.domain.*");
        assertThat(xpath.evaluate("configuration/targetTests/param", execution)).isEqualTo("ru.campusrating.poll.*Test");
        assertThat(xpath.evaluate("configuration/reportsDirectory", execution)).endsWith("/pit-reports/poll");
        assertThat(xpath.evaluate("//profile[id='quality']//plugin[artifactId='pitest-maven']/configuration/mutationThreshold", document))
                .isEqualTo("70");
    }

    @Test
    void everyDemoPollInsertIgnoresConflicts() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/changelog/demo/202611131300-demo-polls.sql"));
        var inserts = sql.split("INSERT INTO");
        assertThat(inserts).hasSize(7);
        for (int index = 1; index < inserts.length; index++) {
            assertThat(inserts[index]).contains("ON CONFLICT DO NOTHING;");
        }
    }

    @Test
    void resultsReadParticipantsAndOptionCountsInOneStatement() throws Exception {
        var views = Files.readString(Path.of("src/main/java/ru/campusrating/poll/internal/PollViews.java"));
        var results = views.substring(views.indexOf("PollResults results("), views.indexOf("List<Long> groupIds("));
        assertThat(results).doesNotContain("repository.participants(", "repository.options(");
        assertThat(results).contains("repository.results(");
        var repository = Files.readString(Path.of("src/main/java/ru/campusrating/poll/internal/PollRepository.java"));
        var query = repository.substring(repository.indexOf("List<OptionResultRow> results("), repository.indexOf("int participants("));
        assertThat(query).contains("select count(*) from poll_participation where poll_id=:poll", "o.vote_count");
        assertThat(query.split("jdbc.sql", -1)).hasSize(2);
    }
}
