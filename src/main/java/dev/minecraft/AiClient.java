package dev.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Minimal Anthropic Messages API client. Never blocks the server thread. */
public final class AiClient {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private AiClient() {}

	public static CompletableFuture<String> ask(AiConfig cfg, String system, String user) {
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "user");
		msg.addProperty("content", user);
		JsonArray messages = new JsonArray();
		messages.add(msg);

		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model);
		body.addProperty("max_tokens", 300);
		body.addProperty("system", system);
		body.add("messages", messages);

		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
			.timeout(Duration.ofSeconds(30))
			.header("content-type", "application/json")
			.header("x-api-key", cfg.key())
			.header("anthropic-version", "2023-06-01")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
			.build();

		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
			if (r.statusCode() / 100 != 2) throw new RuntimeException("Anthropic API returned HTTP " + r.statusCode());
			JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
			return root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		});
	}
}
