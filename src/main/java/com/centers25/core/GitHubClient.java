package com.centers25.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class GitHubClient {
    private final HttpClient http;
    private final Duration timeout;
    private final String token;
    private final long limit;

    GitHubClient(Duration timeout, String token, long limit) {
        this.timeout = timeout;
        this.token = token;
        this.limit = limit;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    Release latest(ManagedPlugin plugin) throws IOException, InterruptedException {
        HttpRequest request = request(URI.create("https://api.github.com/repos/" + plugin.repository() + "/releases/latest"), "application/vnd.github+json");
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub release request returned " + response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray assets = json.getAsJsonArray("assets");
        for (JsonElement item : assets) {
            JsonObject asset = item.getAsJsonObject();
            if (!plugin.asset().equals(asset.get("name").getAsString())) continue;
            long size = asset.get("size").getAsLong();
            if (size < 1 || size > limit) throw new IOException("Release asset exceeds the download limit");
            return new Release(json.get("tag_name").getAsString(), URI.create(asset.get("url").getAsString()), size);
        }
        throw new IOException("Release asset not found: " + plugin.asset());
    }

    void download(Release release, Path target) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request(release.url(), "application/octet-stream"), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("GitHub download returned " + response.statusCode());
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                if (total > limit) throw new IOException("Release asset exceeds the download limit");
                output.write(buffer, 0, read);
            }
            if (total != release.size()) throw new IOException("Release asset size mismatch");
        }
    }

    private HttpRequest request(URI uri, String accept) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept)
                .header("User-Agent", "friendsmp-plugincore").header("X-GitHub-Api-Version", "2022-11-28").GET();
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        return request.build();
    }

    record Release(String tag, URI url, long size) {
    }
}
