package com.codenavigator.indexer;

import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptIndexerTest {

    private final TypeScriptIndexer indexer = new TypeScriptIndexer();
    private final Path sampleDir = Paths.get("src/test/resources/sample-ts");

    @Test
    void detectsService() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.service.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.FE_SERVICE);
        assertThat(nodes.getFirst().name()).isEqualTo("WorkerService");
    }

    @Test
    void detectsComponent() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker-list.component.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().type()).isEqualTo(NodeType.FE_COMPONENT);
        assertThat(nodes.getFirst().name()).isEqualTo("WorkerListComponent");
    }

    @Test
    void detectsModel() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.model.ts"));
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting("name").containsExactly("Worker", "CreateWorkerRequest");
        assertThat(nodes).allMatch(n -> n.type() == NodeType.FE_MODEL);
    }

    @Test
    void extractsApiUrl() {
        var nodes = indexer.indexFile(sampleDir.resolve("worker.service.ts"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().codeSnippet()).contains("/workers");
    }

    @Test
    void detectsPipe(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("money.pipe.ts");
        Files.writeString(f, "@Pipe({ name: 'money' })\nexport class MoneyPipe {}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_PIPE);
        assertThat(nodes.getFirst().name()).isEqualTo("MoneyPipe");
    }

    @Test
    void detectsInterceptor(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("app-http.interceptor.ts");
        Files.writeString(f, "@Injectable()\nexport class AppHttpInterceptor implements HttpInterceptor {}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_INTERCEPTOR);
        assertThat(nodes.getFirst().name()).isEqualTo("AppHttpInterceptor");
    }

    @Test
    void detectsGuard(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("salescloud.guard.ts");
        Files.writeString(f, "@Injectable({ providedIn: 'root' })\nexport class SalescloudGuard implements CanActivate {}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_GUARD);
    }

    // Rule-5 behaviour change: an @Injectable with no HttpClient is now FE_SERVICE
    // (previously such files produced zero nodes).
    @Test
    void injectableWithoutHttpClientIsNowService(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("foo.service.ts");
        Files.writeString(f, "@Injectable()\nexport class FooService {}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_SERVICE);
    }

    @Test
    void detectsUndecoratedClass(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("beneficiary.ts");
        Files.writeString(f, "export class Beneficiary {\n  name: string;\n}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_CLASS);
        assertThat(nodes.getFirst().name()).isEqualTo("Beneficiary");
    }

    @Test
    void detectsEnum(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("beneficiary-type.ts");
        Files.writeString(f, "export enum BeneficiaryType { PERSON, ORGANIZATION }\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_ENUM);
    }

    @Test
    void detectsMultipleConstants(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("messages.const.ts");
        Files.writeString(f, "export const PEP = 'x';\nexport const FOO = 1;\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("name").containsExactly("PEP", "FOO");
        assertThat(nodes).allMatch(n -> n.type() == NodeType.FE_CONSTANT);
    }

    @Test
    void detectsValidatorByType(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("phone.ts");
        Files.writeString(f, "export const phoneValidator: ValidatorFn = (c) => null;\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_VALIDATOR);
        assertThat(nodes.getFirst().name()).isEqualTo("phoneValidator");
    }

    @Test
    void detectsValidatorByFilename(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("split.validator.ts");
        Files.writeString(f, "export function buildSplitValidator() { return null; }\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_VALIDATOR);
        assertThat(nodes.getFirst().name()).isEqualTo("buildSplitValidator");
    }

    @Test
    void decoratedClassIsNotAlsoFeClass(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.component.ts");
        Files.writeString(f, "@Component({ selector: 'a' })\nexport class AComponent {}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_COMPONENT);
    }

    @Test
    void multiNodeFileYieldsAllTypes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mixed.ts");
        Files.writeString(f, "export class Beneficiary { name: string; }\n"
                + "export enum BeneficiaryType { PERSON, ORGANIZATION }\n"
                + "export const A = 1;\nexport const B = 2;\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).hasSize(4);
        assertThat(nodes).extracting("type").containsExactlyInAnyOrder(
                NodeType.FE_CLASS, NodeType.FE_ENUM, NodeType.FE_CONSTANT, NodeType.FE_CONSTANT);
    }
}
