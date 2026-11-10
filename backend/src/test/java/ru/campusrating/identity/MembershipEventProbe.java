package ru.campusrating.identity;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;

@TestConfiguration(proxyBeanMethods = false)
class MembershipEventProbe {
    @Bean Listener membershipEventListener() { return new Listener(); }

    static class Listener {
        private final ConcurrentLinkedQueue<MembershipActivated> committed = new ConcurrentLinkedQueue<>();

        public List<MembershipActivated> committed() { return List.copyOf(committed); }

        @ApplicationModuleListener
        public void membershipActivated(MembershipActivated event) { committed.add(event); }
    }
}
