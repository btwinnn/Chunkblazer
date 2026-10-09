/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer.api;

import static com.chunkblazer.Strings.t;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.chunkblazer.ChunkBlazerConfig;
import com.chunkblazer.ChunkBlazerPlugin;
import com.chunkblazer.GameMode;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP client for ChunkBlazer API communications.
 * Handles all server-side verification requests.
 */
@Slf4j
@Singleton
public class ChunkBlazerApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String CLIENT_VERSION = ChunkBlazerPlugin.VERSION;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ChunkBlazerConfig config;

	/**
	 * The player's API key, obtained on first login and stored locally.
	 * Used for authenticated requests after initial registration.
	 */
	@Getter
	@Setter
	private String playerApiKey;

	@Inject
	public ChunkBlazerApiClient(ChunkBlazerConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		this.gson = gson;
		// Derive from RuneLite's injected client (shared connection pool +
		// dispatcher) rather than constructing a fresh OkHttpClient — the Plugin
		// Hub reviewer requires reusing the injected instance. We only override
		// the timeouts for our own endpoints.
		this.httpClient = okHttpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			// Never follow a redirect: our requests carry the X-API-Key credential in
			// a custom header, which OkHttp does NOT strip on a cross-host redirect, so
			// a rogue/compromised 3xx could leak the key to another host. We only ever
			// talk to our own API and never need a redirect, so refuse them outright.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
	}

	/**
	 * The single gated chokepoint for every asynchronous networking call. Its body
	 * is only the opt-in check and the call, so no request can reach the network
	 * without {@code serverSyncEnabled} being true (Plugin Hub 3rd-party-networking
	 * rule). Callers still return-early on the same flag first, so a disabled call
	 * completes its future correctly rather than being silently dropped here; this
	 * gate is the belt-and-suspenders that also covers any future call site.
	 */
	private void enqueueGated(Request req, Callback cb)
	{
		if (!config.apiEnabled())
		{
			return;
		}
		httpClient.newCall(req).enqueue(cb);
	}

	/** A POST of {@code json} to our API, carrying the account key when there is one. */
	private Request request(String path, String apiKey, String json)
	{
		Request.Builder b = new Request.Builder()
			.url(config.apiBaseUrl() + path)
			.post(RequestBody.create(JSON, json));
		if (apiKey != null)
		{
			b.addHeader("X-API-Key", apiKey);
		}
		return b.build();
	}

	/**
	 * POST {@code body} as JSON and parse a 2xx answer as {@code type}. Anything else completes
	 * with {@code fallback}, given the HTTP status and body (status 0 when no usable answer came).
	 */
	private <T> CompletableFuture<T> postJson(String path, String apiKey, Object body, Class<T> type,
		BiFunction<Integer, String, T> fallback)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		enqueueGated(request(path, apiKey, gson.toJson(body)), new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("{} request failed: {}", path, e.getMessage());
				future.complete(fallback.apply(0, ""));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					String text = response.body() != null ? response.body().string() : "";
					if (response.isSuccessful())
					{
						future.complete(gson.fromJson(text, type));
						return;
					}
					log.warn("{} returned {}: {}", path, response.code(), text);
					future.complete(fallback.apply(response.code(), text));
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("{} response unreadable: {}", path, e.toString());
					future.complete(fallback.apply(0, ""));
				}
			}
		});
		return future;
	}

	/** The key authenticated calls send. Never null. */
	public String currentApiKey()
	{
		String key = (playerApiKey != null && !playerApiKey.isEmpty()) ? playerApiKey : config.apiKey();
		return key == null ? "" : key;
	}

	/** The {@code error} field of our JSON error body, or null. */
	static String errorCode(Gson gson, String body)
	{
		try
		{
			JsonObject obj = gson.fromJson(body, JsonObject.class);
			JsonElement err = obj == null ? null : obj.get("error");
			return err != null && err.isJsonPrimitive() ? err.getAsString() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Retry-After (seconds form only) in ms, else 0. */
	static long parseRetryAfterMs(String header)
	{
		if (header == null)
		{
			return 0;
		}
		try
		{
			long seconds = Long.parseLong(header.trim());
			return seconds > 0 ? seconds * 1000L : 0;
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	// ==================== Player Account Endpoints ====================

	/**
	 * Login or register a player. Called when the player logs into the game.
	 * If the player is new, they will be registered and receive an API key.
	 * If existing, their full state (mode, points, regions, tasks) is returned.
	 *
	 * @param rsn The player's RuneScape name
	 * @param rsnHash SHA-256 hash of the lowercase RSN
	 * @param accountHash SHA-256 of the account id, or null before it is known
	 * @return CompletableFuture with the login response
	 */
	public CompletableFuture<PlayerLoginResponse> login(String rsn, String rsnHash, String accountHash)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(PlayerLoginResponse.offline());
		}

		CompletableFuture<PlayerLoginResponse> future = new CompletableFuture<>();

		// Send the stored key (if we have one) so the server authenticates by it and
		// reconciles a renamed display name onto this same account, instead of the
		// public rsn_hash creating a new empty account. Same key the authenticated
		// calls use: the per-account loaded key, else the visible recovery field.
		String storedKey = (playerApiKey != null && !playerApiKey.isEmpty()) ? playerApiKey : config.apiKey();

		PlayerLoginRequest request = PlayerLoginRequest.builder()
			.rsn(rsn)
			.rsnHash(rsnHash)
			.clientVersion(CLIENT_VERSION)
			.apiKey(storedKey != null && !storedKey.isEmpty() ? storedKey : null)
			.accountHash(accountHash)
			.build();

		Request httpRequest = request("/api/player/login", null, gson.toJson(request)).newBuilder()
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.build();

		enqueueGated(httpRequest, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Login request failed: {}", e.getMessage());
				future.complete(offlineLogin(ApiOutcome.TRANSIENT, 0));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				// Every path must complete the future, or the login chain hangs.
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						PlayerLoginResponse loginResponse = gson.fromJson(body, PlayerLoginResponse.class);
						if (loginResponse == null)
						{
							log.warn("Login returned an empty body");
							future.complete(offlineLogin(ApiOutcome.TRANSIENT, 0));
							return;
						}
						loginResponse.setOutcome(ApiOutcome.SUCCESS);

						// Store the API key if this is a new registration
						if (loginResponse.getApiKey() != null)
						{
							playerApiKey = loginResponse.getApiKey();
						}

						future.complete(loginResponse);
					}
					else
					{
						log.warn("Login returned error {}: {}", response.code(), body);
						future.complete(offlineLogin(
							ApiOutcome.classify(response.code(), errorCode(gson, body)),
							parseRetryAfterMs(response.header("Retry-After"))));
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Login response unreadable: {}", e.toString());
					future.complete(offlineLogin(ApiOutcome.TRANSIENT, 0));
				}
			}
		});

		return future;
	}

	/**
	 * Start an RSN-ownership verification handshake. Server either says the
	 * player is already verified, or issues a 6-char nonce + the exact chat
	 * phrase the player needs to type in-game.
	 */
	public CompletableFuture<VerifyStartResponse> verifyStart()
	{
		String key = currentApiKey();
		if (!config.apiEnabled() || key.isEmpty())
		{
			return CompletableFuture.completedFuture(VerifyStartResponse.offline());
		}
		return postJson("/api/player/verify/start", key, new JsonObject(), VerifyStartResponse.class,
			(code, text) -> VerifyStartResponse.offline());
	}

	/**
	 * Submit the nonce we witnessed the local player typing in chat. On
	 * success the server flips players.verified=true and we get
	 * verified=true back.
	 */
	public CompletableFuture<VerifyResponse> verify(String nonce)
	{
		String key = currentApiKey();
		if (!config.apiEnabled() || key.isEmpty())
		{
			return CompletableFuture.completedFuture(VerifyResponse.offline());
		}
		return postJson("/api/player/verify", key, new VerifyRequestBody(nonce), VerifyResponse.class, (code, text) ->
		{
			if (code == 0)
			{
				return VerifyResponse.offline();
			}
			VerifyResponse r = new VerifyResponse();
			r.setVerified(false);
			r.setMessage("server rejected: " + code);
			return r;
		});
	}

	private static class VerifyRequestBody
	{
		final String nonce;
		VerifyRequestBody(String nonce)
		{
			this.nonce = nonce;
		}
	}

	/**
	 * Ask the server whether this account qualifies for a Full Nuzlocke start
	 * (a fresh "level 3" account). The server is authoritative — it re-derives
	 * the verdict from the submitted snapshot. Used as a pre-check so the plugin
	 * can show the player the right message before starting the lock handshake.
	 *
	 * @param snapshot the live account state read from the game client
	 * @return CompletableFuture with the eligibility verdict
	 */
	public CompletableFuture<NuzlockeEligibilityResponse> checkNuzlockeEligibility(EligibilitySnapshot snapshot)
	{
		String key = currentApiKey();
		if (!config.apiEnabled() || key.isEmpty())
		{
			return CompletableFuture.completedFuture(NuzlockeEligibilityResponse.offline());
		}
		return postJson("/api/player/nuzlocke/eligibility", key, new EligibilityRequestBody(snapshot),
			NuzlockeEligibilityResponse.class, (code, text) -> NuzlockeEligibilityResponse.offline());
	}

	private static class EligibilityRequestBody
	{
		final EligibilitySnapshot eligibility;
		EligibilityRequestBody(EligibilitySnapshot eligibility)
		{
			this.eligibility = eligibility;
		}
	}

	/**
	 * Permanently lock the player's game mode. CASUAL locks immediately; for
	 * NUZLOCKE callers must use {@link #lockGameMode(GameMode, EligibilitySnapshot)}
	 * so the server can re-validate fresh-account eligibility.
	 */
	public CompletableFuture<LockModeResponse> lockGameMode(GameMode mode)
	{
		return lockGameMode(mode, null);
	}

	/**
	 * Permanently lock the player's game mode, optionally carrying the Nuzlocke
	 * eligibility snapshot. This cannot be undone (except by a server admin).
	 *
	 * @param mode The game mode to lock (CASUAL or NUZLOCKE)
	 * @param eligibility Fresh-account snapshot; required by the server for
	 *                    NUZLOCKE, ignored (pass null) for CASUAL
	 * @return CompletableFuture with the lock response
	 */
	public CompletableFuture<LockModeResponse> lockGameMode(GameMode mode, EligibilitySnapshot eligibility)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(LockModeResponse.offline(mode));
		}
		String key = currentApiKey();
		if (key.isEmpty())
		{
			log.warn(t("api.log.noKey"));
			return CompletableFuture.completedFuture(LockModeResponse.error(t("api.noLogin")));
		}
		LockModeRequest request = LockModeRequest.builder()
			.gameMode(mode.name())
			.eligibility(eligibility)
			.build();
		return postJson("/api/player/lock-mode", key, request, LockModeResponse.class, (code, text) ->
		{
			if (code == 0)
			{
				return LockModeResponse.offline(mode);
			}
			// The server explains a refused lock in the same shape.
			try
			{
				LockModeResponse refused = gson.fromJson(text, LockModeResponse.class);
				if (refused != null)
				{
					return refused;
				}
			}
			catch (RuntimeException e)
			{
				// not JSON: report the status instead
			}
			return LockModeResponse.error("Server error: " + code);
		});
	}

	// ==================== Task/Event Reporting Endpoints ====================

	/**
	 * Report an NPC kill to the server for verification.
	 */
	/**
	 * Report that this account lost hardcore status. Completes true once the server
	 * has it (a repeat is a harmless no-op there), false on any failure so the
	 * caller can keep it and retry.
	 */
	public CompletableFuture<Boolean> reportHcimDeath(HcimDeathReport report)
	{
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		if (!config.apiEnabled())
		{
			future.complete(false);
			return future;
		}
		Request httpRequest = request("/api/player/death", currentApiKey(), gson.toJson(report));
		enqueueGated(httpRequest, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("HCIM death report failed: {}", e.getMessage());
				future.complete(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					future.complete(response.isSuccessful());
				}
			}
		});
		return future;
	}
	public CompletableFuture<TaskVerificationResponse> reportNpcKill(NpcKillReport report)
	{
		return reportTask("/api/v1/events/npc-kill", report, report.getTaskId());
	}

	/**
	 * Report a skill level/XP change for verification.
	 */
	public CompletableFuture<TaskVerificationResponse> reportSkillChange(SkillChangeReport report)
	{
		return reportTask("/api/v1/events/skill-change", report, report.getTaskId());
	}

	/**
	 * Report item equipped for verification.
	 * This endpoint allows the server to verify that:
	 * - The item was legitimately equipped (was in inventory)
	 * - The player meets level requirements
	 * - The equip happened at a valid location/region
	 * - The timing is consistent with normal gameplay
	 */
	public CompletableFuture<TaskVerificationResponse> reportItemEquipped(ItemEquippedReport report)
	{
		return reportTask("/api/v1/events/item-equipped", report, report.getTaskId());
	}

	private CompletableFuture<TaskVerificationResponse> reportTask(String path, Object report, String taskId)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(TaskVerificationResponse.offlineSuccess(taskId));
		}
		return postJson(path, currentApiKey(), report, TaskVerificationResponse.class,
			(code, text) -> TaskVerificationResponse.error(code == 0 ? "Network error" : "Server error: " + code));
	}

	/**
	 * Sync player state with server (called periodically or on login).
	 */
	public CompletableFuture<PlayerSyncResponse> syncPlayerState(PlayerSyncRequest request)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(new PlayerSyncResponse());
		}

		CompletableFuture<PlayerSyncResponse> future = new CompletableFuture<>();

		Request httpRequest = request("/api/v1/player/sync", currentApiKey(), gson.toJson(request));

		enqueueGated(httpRequest, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Player sync failed: {}", e.getMessage());
				future.complete(failedSync(ApiOutcome.TRANSIENT, 0));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						PlayerSyncResponse syncResponse = gson.fromJson(body, PlayerSyncResponse.class);
						if (syncResponse == null)
						{
							log.warn(t("api.log.syncEmpty"));
							future.complete(failedSync(ApiOutcome.TRANSIENT, 0));
							return;
						}
						syncResponse.setOutcome(ApiOutcome.SUCCESS);
						future.complete(syncResponse);
					}
					else
					{
						log.warn(t("api.log.syncError"), response.code(), body);
						future.complete(failedSync(
							ApiOutcome.classify(response.code(), errorCode(gson, body)),
							parseRetryAfterMs(response.header("Retry-After"))));
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Player sync response unreadable: {}", e.toString());
					future.complete(failedSync(ApiOutcome.TRANSIENT, 0));
				}
			}
		});

		return future;
	}

	private static PlayerLoginResponse offlineLogin(ApiOutcome outcome, long retryAfterMs)
	{
		PlayerLoginResponse r = PlayerLoginResponse.offline();
		r.setOutcome(outcome);
		r.setRetryAfterMs(retryAfterMs);
		return r;
	}

	private static PlayerSyncResponse failedSync(ApiOutcome outcome, long retryAfterMs)
	{
		PlayerSyncResponse r = new PlayerSyncResponse();
		r.setOutcome(outcome);
		r.setRetryAfterMs(retryAfterMs);
		return r;
	}
}
