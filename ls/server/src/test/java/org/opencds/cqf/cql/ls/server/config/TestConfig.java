package org.opencds.cqf.cql.ls.server.config;

import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.TestContentService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestConfig {
    @Bean(name = "fileContentService")
    @Primary
    public ContentService fileContentService() {
        return new TestContentService();
    }

}
