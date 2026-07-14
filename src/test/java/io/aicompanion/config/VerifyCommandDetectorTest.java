package io.aicompanion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VerifyCommandDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void packageJsonBuildsLintTypecheckTestPipeline() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {
              "name": "demo",
              "scripts": {
                "build": "tsc -p .",
                "lint": "eslint .",
                "typecheck": "tsc --noEmit",
                "test": "vitest run"
              }
            }
            """);
        assertEquals(
            List.of("npm run lint", "npm run typecheck", "npm test"),
            VerifyCommandDetector.detect(tempDir));
    }

    @Test
    void packageJsonPrefersTypeCheckAlias() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {"scripts":{"lint":"eslint .","type-check":"tsc -b","test":"jest"}}
            """);
        assertEquals(
            List.of("npm run lint", "npm run type-check", "npm test"),
            VerifyCommandDetector.detect(tempDir));
    }

    @Test
    void packageJsonWithoutCheapChecksReturnsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {"scripts":{"test":"jest","start":"node index.js"}}
            """);
        assertTrue(VerifyCommandDetector.detect(tempDir).isEmpty(),
            "single test script should fall back to test_command auto-detect");
    }

    @Test
    void packageJsonHandlesNestedBracesInScriptValues() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {
              "scripts": {
                "lint": "node -e \\"console.log({a:1})\\"",
                "test": "jest"
              }
            }
            """);
        assertEquals(
            List.of("npm run lint", "npm test"),
            VerifyCommandDetector.fromPackageJson(tempDir.resolve("package.json")));
    }

    @Test
    void makefileBuildsFailFastPipeline() throws IOException {
        Files.writeString(tempDir.resolve("Makefile"), """
            .PHONY: lint test build
            lint:
            	ruff check .
            typecheck:
            	mypy src
            test:
            	pytest
            build:
            	echo build
            """);
        assertEquals(
            List.of("make lint", "make typecheck", "make test"),
            VerifyCommandDetector.detect(tempDir));
    }

    @Test
    void makefileWithoutCheapTargetsReturnsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("Makefile"), """
            test:
            	pytest
            """);
        assertTrue(VerifyCommandDetector.detect(tempDir).isEmpty());
    }

    @Test
    void readJsonObjectKeysFindsScripts() {
        Set<String> keys = VerifyCommandDetector.readJsonObjectKeys(
            "{\"scripts\":{\"lint\":\"x\",\"test\":\"y\"},\"name\":\"n\"}", "scripts");
        assertEquals(Set.of("lint", "test"), keys);
    }

    @Test
    void explicitVerifyCommandsStillWinInConfigLoader() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {"scripts":{"lint":"eslint","typecheck":"tsc","test":"jest"}}
            """);
        Config cfg = ConfigLoader.load(
            Map.of("project_dir", tempDir.toString(),
                   "verify_commands", "npm test"),
            Map.of());
        assertEquals(List.of("npm test"), cfg.verifyCommands());
        assertEquals(List.of("npm test"), cfg.effectiveVerifyCommands());
    }

    @Test
    void configLoaderAutoDetectsFailFastWhenUnset() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
            {"scripts":{"lint":"eslint","typecheck":"tsc","test":"jest"}}
            """);
        Config cfg = ConfigLoader.load(
            Map.of("project_dir", tempDir.toString()),
            Map.of());
        assertEquals(
            List.of("npm run lint", "npm run typecheck", "npm test"),
            cfg.effectiveVerifyCommands());
    }
}
