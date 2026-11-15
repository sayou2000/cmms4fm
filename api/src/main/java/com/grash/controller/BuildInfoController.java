package com.grash.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which commit this api was built from, for the build stamp in the sidebar.
 *
 * <p>The values are baked into the image by CI (see {@code api/Dockerfile} and
 * {@code deploy.yml}) and are empty for a local build. The frontend compares the sha with
 * its own, so a deploy that left the two containers on different builds becomes visible.
 *
 * <p>Fork-only file. Authenticated like every other endpoint, because only the logged-in
 * sidebar asks; {@code @Hidden} keeps it out of the OpenAPI document and therefore out of
 * the MCP server's tool list.
 */
@RestController
@RequestMapping("/build-info")
@Hidden
public class BuildInfoController {

    @Value("${GIT_SHA:}")
    private String sha;

    @Value("${GIT_COMMIT_TIME:}")
    private String time;

    public record BuildInfo(String sha, String time) {
    }

    @GetMapping("")
    public BuildInfo getBuildInfo() {
        return new BuildInfo(sha, time);
    }
}
