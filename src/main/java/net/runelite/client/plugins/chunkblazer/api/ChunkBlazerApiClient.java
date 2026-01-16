package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;
import net.runelite.client.plugins.chunkblazer.GameMode;
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
	private static final String CLIENT_VERSION = "1.0.0";

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
	public ChunkBlazerApiClient(ChunkBlazerConfig config, Gson gson)
	{
		this.config = config;
		this.gson = gson;
		this.httpClient = new OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
	}

	// ==================== Player Account Endpoints ====================

	/**
	 * Login or register a player. Called when the player logs into the game.
	 * If the player is new, they will be registered and receive an API key.
	 * If existing, their full state (mode, points, regions, tasks) is returned.
	 *
	 * @param rsn The player's RuneScape name
	 * @param rsnHash SHA-256 hash of the lowercase RSN
	 * @return CompletableFuture with the login response
	 */
	public CompletableFuture<PlayerLoginResponse> login(String rsn, String rsnHash)
	{
		if (!config.apiEnabled())
		{
			log.debug("API disabled, returning offline login");
			return CompletableFuture.completedFuture(PlayerLoginResponse.offline());
		}

		CompletableFuture<PlayerLoginResponse> future = new CompletableFuture<>();

		PlayerLoginRequest request = PlayerLoginRequest.builder()
			.rsn(rsn)
			.rsnHash(rsnHash)
			.clientVersion(CLIENT_VERSION)
			.build();

		String url = config.apiBaseUrl() + "/api/player/login";
		String json = gson.toJson(request);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("Content-Type", "application/json")
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Login request failed: {}", e.getMessage());
				future.complete(PlayerLoginResponse.offline());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						PlayerLoginResponse loginResponse = gson.fromJson(body, PlayerLoginResponse.class);

						// Store the API key if this is a new registration
						if (loginResponse.getApiKey() != null)
						{
							playerApiKey = loginResponse.getApiKey();
							log.info("Received new API key for player");
						}

						future.complete(loginResponse);
					}
					else
					{
						log.warn("Login returned error {}: {}", response.code(), body);
						future.complete(PlayerLoginResponse.offline());
					}
				}
			}
		});

		return future;
	}

	/**
	 * Permanently lock the player's game mode.
	 * This cannot be undone (except by server admin).
	 *
	 * @param mode The game mode to lock (CASUAL or NUZLOCKE)
	 * @return CompletableFuture with the lock response
	 */
	public CompletableFuture<LockModeResponse> lockGameMode(GameMode mode)
	{
		if (!config.apiEnabled())
		{
			log.debug("API disabled, returning offline mode lock");
			return CompletableFuture.completedFuture(LockModeResponse.offline(mode));
		}

		if (playerApiKey == null || playerApiKey.isEmpty())
		{
			log.warn("Cannot lock mode: no API key (player not logged in via API)");
			return CompletableFuture.completedFuture(
				LockModeResponse.error("Not logged in to server"));
		}

		CompletableFuture<LockModeResponse> future = new CompletableFuture<>();

		LockModeRequest request = LockModeRequest.builder()
			.gameMode(mode.name())
			.build();

		String url = config.apiBaseUrl() + "/api/player/lock-mode";
		String json = gson.toJson(request);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("Content-Type", "application/json")
			.addHeader("X-API-Key", playerApiKey)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Lock mode request failed: {}", e.getMessage());
				future.complete(LockModeResponse.offline(mode));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						LockModeResponse lockResponse = gson.fromJson(body, LockModeResponse.class);
						future.complete(lockResponse);
					}
					else
					{
						log.warn("Lock mode returned error {}: {}", response.code(), body);
						// Try to parse error response
						try
						{
							LockModeResponse errorResponse = gson.fromJson(body, LockModeResponse.class);
							future.complete(errorResponse);
						}
						catch (Exception e)
						{
							future.complete(LockModeResponse.error("Server error: " + response.code()));
						}
					}
				}
			}
		});

		return future;
	}

	/**
	 * Get the leaderboard for a specific game mode.
	 *
	 * @param mode The game mode (CASUAL or NUZLOCKE)
	 * @param limit Maximum number of entries to return
	 * @param offset Offset for pagination
	 * @return CompletableFuture with the leaderboard response
	 */
	public CompletableFuture<LeaderboardResponse> getLeaderboard(GameMode mode, int limit, int offset)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(LeaderboardResponse.empty(mode.name()));
		}

		CompletableFuture<LeaderboardResponse> future = new CompletableFuture<>();

		String url = String.format("%s/api/leaderboard?mode=%s&limit=%d&offset=%d",
			config.apiBaseUrl(), mode.name(), limit, offset);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.get()
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Leaderboard request failed: {}", e.getMessage());
				future.complete(LeaderboardResponse.empty(mode.name()));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						LeaderboardResponse lbResponse = gson.fromJson(body, LeaderboardResponse.class);
						future.complete(lbResponse);
					}
					else
					{
						log.warn("Leaderboard returned error {}: {}", response.code(), body);
						future.complete(LeaderboardResponse.empty(mode.name()));
					}
				}
			}
		});

		return future;
	}

	/**
	 * Get the current player's rank and nearby players.
	 *
	 * @return CompletableFuture with the rank response
	 */
	public CompletableFuture<PlayerRankResponse> getPlayerRank()
	{
		if (!config.apiEnabled() || playerApiKey == null)
		{
			return CompletableFuture.completedFuture(PlayerRankResponse.unranked());
		}

		CompletableFuture<PlayerRankResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/player/rank";

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey)
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.get()
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Player rank request failed: {}", e.getMessage());
				future.complete(PlayerRankResponse.unranked());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						PlayerRankResponse rankResponse = gson.fromJson(body, PlayerRankResponse.class);
						future.complete(rankResponse);
					}
					else
					{
						log.warn("Player rank returned error {}: {}", response.code(), body);
						future.complete(PlayerRankResponse.unranked());
					}
				}
			}
		});

		return future;
	}

	// ==================== Player Discovery Endpoints ====================

	/**
	 * Send a heartbeat to keep the player marked as online.
	 * Should be called every 30-60 seconds while logged in.
	 *
	 * @param world The current world
	 * @param regionId The current region
	 * @param isVisible Whether the player wants to be visible
	 * @return CompletableFuture with the response
	 */
	public CompletableFuture<Void> sendHeartbeat(int world, int regionId, boolean isVisible)
	{
		if (!config.apiEnabled() || playerApiKey == null)
		{
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<Void> future = new CompletableFuture<>();

		HeartbeatRequest request = HeartbeatRequest.builder()
			.world(world)
			.regionId(regionId)
			.isVisible(isVisible)
			.build();

		String url = config.apiBaseUrl() + "/api/player/heartbeat";
		String json = gson.toJson(request);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("Content-Type", "application/json")
			.addHeader("X-API-Key", playerApiKey)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Heartbeat failed: {}", e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
				future.complete(null);
			}
		});

		return future;
	}

	/**
	 * Get list of online ChunkBlazer players.
	 *
	 * @param world Optional world filter (-1 for all worlds)
	 * @return CompletableFuture with the online players response
	 */
	public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(int world)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(OnlinePlayersResponse.empty());
		}

		CompletableFuture<OnlinePlayersResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/players/online";
		if (world > 0)
		{
			url += "?world=" + world;
		}

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.get()
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Get online players failed: {}", e.getMessage());
				future.complete(OnlinePlayersResponse.empty());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						OnlinePlayersResponse onlineResponse = gson.fromJson(body, OnlinePlayersResponse.class);
						future.complete(onlineResponse);
					}
					else
					{
						log.warn("Get online players returned error {}: {}", response.code(), body);
						future.complete(OnlinePlayersResponse.empty());
					}
				}
			}
		});

		return future;
	}

	/**
	 * Get online players on a specific world.
	 *
	 * @param world The world to query
	 * @return CompletableFuture with the online players response
	 */
	public CompletableFuture<OnlinePlayersResponse> getPlayersOnWorld(int world)
	{
		return getOnlinePlayers(world);
	}

	/**
	 * Notify the server that the player is going offline.
	 */
	public CompletableFuture<Void> goOffline()
	{
		if (!config.apiEnabled() || playerApiKey == null)
		{
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<Void> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/player/offline";

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey)
			.post(RequestBody.create(JSON, "{}"))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Go offline failed: {}", e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
				future.complete(null);
			}
		});

		return future;
	}

	// ==================== Task/Event Reporting Endpoints ====================

	/**
	 * Verify a task completion with the server.
	 *
	 * @param request The verification request containing task and evidence data
	 * @return CompletableFuture with the server response
	 */
	public CompletableFuture<TaskVerificationResponse> verifyTaskCompletion(TaskVerificationRequest request)
	{
		if (!config.apiEnabled())
		{
			// API disabled - return offline success for testing
			log.debug("API disabled, returning offline verification");
			return CompletableFuture.completedFuture(
				TaskVerificationResponse.offlineSuccess(request.getTaskId())
			);
		}

		CompletableFuture<TaskVerificationResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/v1/tasks/verify";
		String json = gson.toJson(request);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.addHeader("X-Client-Version", CLIENT_VERSION)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("API request failed: {}", e.getMessage());
				future.complete(TaskVerificationResponse.error("Network error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						TaskVerificationResponse verifyResponse = gson.fromJson(body, TaskVerificationResponse.class);
						future.complete(verifyResponse);
					}
					else
					{
						log.warn("API returned error {}: {}", response.code(), body);
						future.complete(TaskVerificationResponse.error("Server error: " + response.code()));
					}
				}
			}
		});

		return future;
	}

	/**
	 * Report an NPC kill to the server for verification.
	 */
	public CompletableFuture<TaskVerificationResponse> reportNpcKill(NpcKillReport report)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(
				TaskVerificationResponse.offlineSuccess(report.getTaskId())
			);
		}

		CompletableFuture<TaskVerificationResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/v1/events/npc-kill";
		String json = gson.toJson(report);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("NPC kill report failed: {}", e.getMessage());
				future.complete(TaskVerificationResponse.error("Network error"));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						TaskVerificationResponse verifyResponse = gson.fromJson(body, TaskVerificationResponse.class);
						future.complete(verifyResponse);
					}
					else
					{
						future.complete(TaskVerificationResponse.error("Server error: " + response.code()));
					}
				}
			}
		});

		return future;
	}

	/**
	 * Report a skill level/XP change for verification.
	 */
	public CompletableFuture<TaskVerificationResponse> reportSkillChange(SkillChangeReport report)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(
				TaskVerificationResponse.offlineSuccess(report.getTaskId())
			);
		}

		CompletableFuture<TaskVerificationResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/v1/events/skill-change";
		String json = gson.toJson(report);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Skill change report failed: {}", e.getMessage());
				future.complete(TaskVerificationResponse.error("Network error"));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						TaskVerificationResponse verifyResponse = gson.fromJson(body, TaskVerificationResponse.class);
						future.complete(verifyResponse);
					}
					else
					{
						future.complete(TaskVerificationResponse.error("Server error: " + response.code()));
					}
				}
			}
		});

		return future;
	}

	/**
	 * Report item obtained for verification.
	 */
	public CompletableFuture<TaskVerificationResponse> reportItemObtained(ItemObtainedReport report)
	{
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(
				TaskVerificationResponse.offlineSuccess(report.getTaskId())
			);
		}

		CompletableFuture<TaskVerificationResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/v1/events/item-obtained";
		String json = gson.toJson(report);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Item obtained report failed: {}", e.getMessage());
				future.complete(TaskVerificationResponse.error("Network error"));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						TaskVerificationResponse verifyResponse = gson.fromJson(body, TaskVerificationResponse.class);
						future.complete(verifyResponse);
					}
					else
					{
						future.complete(TaskVerificationResponse.error("Server error: " + response.code()));
					}
				}
			}
		});

		return future;
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
		if (!config.apiEnabled())
		{
			return CompletableFuture.completedFuture(
				TaskVerificationResponse.offlineSuccess(report.getTaskId())
			);
		}

		CompletableFuture<TaskVerificationResponse> future = new CompletableFuture<>();

		String url = config.apiBaseUrl() + "/api/v1/events/item-equipped";
		String json = gson.toJson(report);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Item equipped report failed: {}", e.getMessage());
				future.complete(TaskVerificationResponse.error("Network error"));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						TaskVerificationResponse verifyResponse = gson.fromJson(body, TaskVerificationResponse.class);
						future.complete(verifyResponse);
					}
					else
					{
						log.warn("Item equipped report returned error {}: {}", response.code(), body);
						future.complete(TaskVerificationResponse.error("Server error: " + response.code()));
					}
				}
			}
		});

		return future;
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

		String url = config.apiBaseUrl() + "/api/v1/player/sync";
		String json = gson.toJson(request);

		Request httpRequest = new Request.Builder()
			.url(url)
			.addHeader("X-API-Key", playerApiKey != null ? playerApiKey : config.apiKey())
			.addHeader("Content-Type", "application/json")
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(httpRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Player sync failed: {}", e.getMessage());
				future.complete(new PlayerSyncResponse());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					String body = response.body() != null ? response.body().string() : "";

					if (response.isSuccessful())
					{
						PlayerSyncResponse syncResponse = gson.fromJson(body, PlayerSyncResponse.class);
						future.complete(syncResponse);
					}
					else
					{
						future.complete(new PlayerSyncResponse());
					}
				}
			}
		});

		return future;
	}
}
