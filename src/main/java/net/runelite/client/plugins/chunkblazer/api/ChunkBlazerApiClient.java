package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;
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

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ChunkBlazerConfig config;

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
			.addHeader("Authorization", "Bearer " + config.apiKey())
			.addHeader("Content-Type", "application/json")
			.addHeader("X-Client-Version", "1.0.0")
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
			.addHeader("Authorization", "Bearer " + config.apiKey())
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
			.addHeader("Authorization", "Bearer " + config.apiKey())
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
			.addHeader("Authorization", "Bearer " + config.apiKey())
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
			.addHeader("Authorization", "Bearer " + config.apiKey())
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
