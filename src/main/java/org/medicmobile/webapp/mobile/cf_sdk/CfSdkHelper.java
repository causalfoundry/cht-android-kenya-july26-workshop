package org.medicmobile.webapp.mobile.cf_sdk;

import static org.medicmobile.webapp.mobile.BuildConfig.DEBUG;
import static org.medicmobile.webapp.mobile.MedicLog.log;
import static org.medicmobile.webapp.mobile.MedicLog.warn;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.lifecycle.Observer;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import io.kenkai.android.sdk.core.CFCoreEvent;
import io.kenkai.android.sdk.core.catalog.catalog_models.CoreCatalogType;
import io.kenkai.android.sdk.core.catalog.catalog_models.UserCatalogModel;
import io.kenkai.android.sdk.core.event_models.event_objects.AppObject;
import io.kenkai.android.sdk.core.event_models.event_objects.IdentifyObject;
import io.kenkai.android.sdk.core.event_models.event_objects.TrackEventObject;
import io.kenkai.android.sdk.core.event_types.AppAction;
import io.kenkai.android.sdk.core.event_types.CoreEventType;
import io.kenkai.android.sdk.core.event_types.IdentifyAction;
import io.kenkai.android.sdk.core.workers.WorkerCaller;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.medicmobile.webapp.mobile.BuildConfig;
import org.medicmobile.webapp.mobile.MedicLog;

public final class CfSdkHelper {
	// Fixed CDN endpoint for CHT instrumentation. Do not change it in downstream apps.
	static final String WEB_INSTRUMENTATION_REMOTE_URL =
		"https://cdn.causalfoundry.ai/cht-echis-kenya/cht-web-instrumentation.js";

	private static final String AUTH_SESSION_COOKIE_NAME = "AuthSession";
	private static final String COUCHDB_USER_ID_PREFIX = "org.couchdb.user:";
	private static final String WEB_INSTRUMENTATION_BRIDGE_NAME = "cf_sdk_android";
	private static final String WEB_INSTRUMENTATION_LOG_PREFIX = "CHT_WEB_EVENT";
	private static final String[] CF_SDK_SYNC_WORK_NAMES = {
		"upload_cf_sdk_events",
		"upload_cf_dim_events"
	};
	private static final String UTC_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	private static final byte SESSION_FIELD_DELIMITER = ':';
	private static final int MAX_TRACK_PAYLOAD_LENGTH = 16 * 1024;
	private static final int MAX_USER_PAYLOAD_LENGTH = 16 * 1024;
	private static final int MAX_DEBUG_MESSAGE_LENGTH = 4 * 1024;
	private static final int MAX_EVENT_COMPONENT_LENGTH = 120;
	private static final int MAX_META_PROPERTIES = 32;
	private static final int MAX_META_KEY_LENGTH = 120;
	private static final int MAX_META_STRING_LENGTH = 512;
	private static final int MAX_USER_FIELD_LENGTH = 256;
	private static final Object SDK_EVENT_LOCK = new Object();
	private static final Map<WebView, WebInstrumentationSession> WEB_INSTRUMENTATION_SESSIONS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final AtomicBoolean SDK_SYNC_OBSERVERS_REGISTERED = new AtomicBoolean();

	private final String userId;
	private final Long timestampSeconds;
	private final String timestampHex;

	private CfSdkHelper(String userId, Long timestampSeconds, String timestampHex) {
		this.userId = userId;
		this.timestampSeconds = timestampSeconds;
		this.timestampHex = timestampHex;
	}

	public static Optional<CfSdkHelper> fromCookieHeader(String cookieHeader) {
		if (cookieHeader == null) {
			return Optional.empty();
		}

		String[] cookies = cookieHeader.split(";");
		for (String cookie : cookies) {
			String[] parts = cookie.trim().split("=", 2);
			if (parts.length == 2 && AUTH_SESSION_COOKIE_NAME.equals(parts[0])) {
				Optional<CfSdkHelper> sessionCookie = fromAuthSession(parts[1]);
				if (sessionCookie.isPresent()) {
					return sessionCookie;
				}
			}
		}

		return fromCouchDbUserId(cookieHeader);
	}

	public static Optional<String> userIdFromCookieHeader(String cookieHeader) {
		return fromCookieHeader(cookieHeader).map(CfSdkHelper::getUserId);
	}

	public static Optional<Long> timestampSecondsFromCookieHeader(String cookieHeader) {
		return fromCookieHeader(cookieHeader).flatMap(CfSdkHelper::getTimestampSeconds);
	}

	public static void logAppOpen() {
		synchronized (SDK_EVENT_LOCK) {
			CFCoreEvent.INSTANCE.logIngest(
				CoreEventType.App,
				new AppObject(AppAction.Open, 0),
				null, null
			);
		}
	}

	public static void logAppClose() {
		synchronized (SDK_EVENT_LOCK) {
			CFCoreEvent.INSTANCE.logIngest(
				CoreEventType.App,
				new AppObject(AppAction.Close, 0),
				null, null
			);
		}
	}

	/** Loads the local script cache and observes CF uploads as a fallback CDN refresh trigger. */
	public static void prepareWebInstrumentation(Context context) {
		Context applicationContext = context.getApplicationContext();
		if (applicationContext == null) {
			applicationContext = context;
		}
		CfWebInstrumentationRepository repository =
			CfWebInstrumentationRepository.getInstance(applicationContext);
		if (!SDK_SYNC_OBSERVERS_REGISTERED.compareAndSet(false, true)) {
			return;
		}

		try {
			WorkManager workManager = WorkManager.getInstance(applicationContext);
			for (String workName : CF_SDK_SYNC_WORK_NAMES) {
				workManager.getWorkInfosForUniqueWorkLiveData(workName).observeForever(
					new SdkSyncRefreshObserver(repository::refreshAsync)
				);
			}
		} catch (IllegalStateException exception) {
			SDK_SYNC_OBSERVERS_REGISTERED.set(false);
			warn(exception,
				"CF SDK sync observer is not ready; web instrumentation setup will retry");
		}
	}

	public static void initWebViewInstrumentation(WebView webView) {
		WebInstrumentationSession session = instrumentationSessionFor(webView);
		Context applicationContext = applicationContextOf(webView.getContext());
		webView.addJavascriptInterface(
			new WebInstrumentationBridge(
				payload -> handleWebEvent(applicationContext, payload),
				session::isAllowed
			),
			WEB_INSTRUMENTATION_BRIDGE_NAME
		);
	}

	private static Context applicationContextOf(Context context) {
		Context applicationContext = context.getApplicationContext();
		return applicationContext == null ? context : applicationContext;
	}

	/** Starts instrumentation only when the new top-level document belongs to the CHT app. */
	public static void startWebInstrumentationPage(
		WebView webView,
		String appUrl,
		String pageUrl
	) {
		if (isTrustedWebInstrumentationUrl(appUrl, pageUrl)) {
			startTrustedWebInstrumentationPage(webView);
		} else {
			clearWebInstrumentationPage(webView);
		}
	}

	/** Pins one script version for this trusted top-level document and injects it early. */
	private static void startTrustedWebInstrumentationPage(WebView webView) {
		WebInstrumentationSession session = instrumentationSessionFor(webView);
		WebInstrumentationPage page = new WebInstrumentationPage();
		session.start(page);
		webView.post(() -> {
			if (!isCurrentPage(webView, session, page)) {
				return;
			}
			CfWebInstrumentationRepository repository =
				CfWebInstrumentationRepository.getInstance(webView.getContext());
			CfWebInstrumentationRepository.ScriptSnapshot snapshot =
				repository.scriptForInjection();
			page.snapshot = snapshot;
			evaluateWebInstrumentation(
				webView,
				repository,
				session,
				page,
				snapshot,
				false
			);
		});
	}

	/** Finishes instrumentation only when the loaded top-level document belongs to the CHT app. */
	public static void injectWebInstrumentation(
		WebView webView,
		String appUrl,
		String pageUrl
	) {
		if (isTrustedWebInstrumentationUrl(appUrl, pageUrl)) {
			injectTrustedWebInstrumentation(webView);
		} else {
			clearWebInstrumentationPage(webView);
		}
	}

	private static void injectTrustedWebInstrumentation(WebView webView) {
		WebInstrumentationSession session = instrumentationSessionFor(webView);
		WebInstrumentationPage existingPage = session.currentPage();
		WebInstrumentationPage page = existingPage == null
			? new WebInstrumentationPage()
			: existingPage;
		if (existingPage == null) {
			session.start(page);
		}
		webView.post(() -> {
			if (!isCurrentPage(webView, session, page)) {
				return;
			}
			CfWebInstrumentationRepository repository =
				CfWebInstrumentationRepository.getInstance(webView.getContext());
			CfWebInstrumentationRepository.ScriptSnapshot snapshot = page.snapshot;
			if (snapshot == null) {
				snapshot = repository.scriptForInjection();
				page.snapshot = snapshot;
			}
			evaluateWebInstrumentation(
				webView,
				repository,
				session,
				page,
				snapshot,
				true
			);
		});
	}

	/** Disables bridge calls and drops the pinned snapshot for an unrelated top-level page. */
	private static void clearWebInstrumentationPage(WebView webView) {
		WebInstrumentationSession session;
		synchronized (WEB_INSTRUMENTATION_SESSIONS) {
			session = WEB_INSTRUMENTATION_SESSIONS.get(webView);
		}
		if (session != null) {
			session.clear();
		}
	}

	private static boolean isTrustedWebInstrumentationUrl(String appUrl, String pageUrl) {
		if (appUrl == null || appUrl.isEmpty() || pageUrl == null || pageUrl.isEmpty()) {
			return false;
		}

		String testedUrl = pageUrl;
		if (testedUrl.regionMatches(true, 0, "blob:", 0, "blob:".length())) {
			testedUrl = testedUrl.substring("blob:".length());
		}

		Uri appUri = Uri.parse(appUrl);
		Uri testedUri = Uri.parse(testedUrl);
		if (!hasHttpOrigin(appUri) || !hasHttpOrigin(testedUri)) {
			return false;
		}

		if (!appUri.getScheme().equalsIgnoreCase(testedUri.getScheme()) ||
				!appUri.getHost().equalsIgnoreCase(testedUri.getHost()) ||
				effectivePort(appUri) != effectivePort(testedUri)) {
			return false;
		}

		String appPath = basePath(appUri);
		String testedPath = encodedPath(testedUri);
		return "/".equals(appPath) ||
			testedPath.equals(appPath) ||
			testedPath.startsWith(appPath + "/");
	}

	private static boolean hasHttpOrigin(Uri uri) {
		String scheme = uri.getScheme();
		return uri.getHost() != null &&
			("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
	}

	private static int effectivePort(Uri uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static String basePath(Uri uri) {
		String path = encodedPath(uri);
		while (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	private static String encodedPath(Uri uri) {
		String path = uri.getEncodedPath();
		return path == null || path.isEmpty() ? "/" : path;
	}

	private static void evaluateWebInstrumentation(
		WebView webView,
		CfWebInstrumentationRepository repository,
		WebInstrumentationSession session,
		WebInstrumentationPage page,
		CfWebInstrumentationRepository.ScriptSnapshot snapshot,
		boolean finalAttempt
	) {
		if (!isCurrentPage(webView, session, page)) {
			return;
		}
		try {
			repository.markCandidateEvaluationStarted(snapshot);
			String scriptAndProbe = snapshot.script + "\n;" +
				repository.readinessProbe(snapshot);
			webView.evaluateJavascript(scriptAndProbe, result -> {
				if (!isCurrentPage(webView, session, page) || !finalAttempt) {
					return;
				}
				boolean ready = "true".equals(result);
				repository.recordInjectionResult(snapshot, ready);
				if (!ready) {
					CfWebInstrumentationRepository.ScriptSnapshot fallback =
						repository.scriptForInjection();
					if (fallback != snapshot && isCurrentPage(webView, session, page)) {
						page.snapshot = fallback;
						evaluateWebInstrumentation(
							webView,
							repository,
							session,
							page,
							fallback,
							false
						);
					}
				}
			});
		} catch (RuntimeException exception) {
			MedicLog.warn(exception,
				"CfSdkHelper :: Unable to inject CF web instrumentation");
		}
	}

	private static WebInstrumentationSession instrumentationSessionFor(WebView webView) {
		synchronized (WEB_INSTRUMENTATION_SESSIONS) {
			WebInstrumentationSession session = WEB_INSTRUMENTATION_SESSIONS.get(webView);
			if (session == null) {
				session = new WebInstrumentationSession();
				WEB_INSTRUMENTATION_SESSIONS.put(webView, session);
			}
			return session;
		}
	}

	@SuppressWarnings("PMD.CompareObjectsWithEquals") // Identity is the navigation-generation token.
	private static boolean isCurrentPage(
		WebView webView,
		WebInstrumentationSession session,
		WebInstrumentationPage page
	) {
		synchronized (WEB_INSTRUMENTATION_SESSIONS) {
			return WEB_INSTRUMENTATION_SESSIONS.get(webView) == session &&
				session.isCurrent(page);
		}
	}

	public String getUserId() {
		return userId;
	}

	public Optional<Long> getTimestampSeconds() {
		return Optional.ofNullable(timestampSeconds);
	}

	public Optional<String> getTimestampHex() {
		return Optional.ofNullable(timestampHex);
	}

	public Optional<String> getTimestampIso8601() {
		return getTimestampSeconds().map(CfSdkHelper::timestampSecondsToIso8601);
	}

	public String getDeduplicationKey() {
		return String.format("%s:%s", userId, getTimestampHex().orElse("no-session-timestamp"));
	}

	public void identifyLogin() {
		synchronized (SDK_EVENT_LOCK) {
			CFCoreEvent.INSTANCE.logIngest(
				CoreEventType.Identify,
				new IdentifyObject(userId, IdentifyAction.Login, "", "", "", null),
				null,
				null
			);
		}
	}

	public String getTimestampLogValue() {
		String isoTimestamp = getTimestampIso8601().orElse("unavailable");
		return getTimestampHex()
			.map(hexTimestamp -> String.format("%s (%s)", isoTimestamp, hexTimestamp))
			.orElse(isoTimestamp);
	}

	public static final class Deduplicator {
		private final LoginIdentifier loginIdentifier;
		private String lastLoggedKey;

		public Deduplicator() {
			this(CfSdkHelper::identifyLogin);
		}

		Deduplicator(LoginIdentifier loginIdentifier) {
			this.loginIdentifier = loginIdentifier;
		}

		public boolean shouldLog(CfSdkHelper sessionCookie) {
			String deduplicationKey = sessionCookie.getDeduplicationKey();
			if (deduplicationKey.equals(lastLoggedKey)) {
				return false;
			}

			lastLoggedKey = deduplicationKey;
			return true;
		}

		public boolean identifyLoginIfNewSession(CfSdkHelper sessionCookie) {
			if (!shouldLog(sessionCookie)) {
				return false;
			}

			loginIdentifier.identifyLogin(sessionCookie);
			return true;
		}

		public void reset() {
			lastLoggedKey = null;
		}
	}

	interface LoginIdentifier {
		void identifyLogin(CfSdkHelper sessionCookie);
	}

	private static final class WebInstrumentationSession {
		private volatile boolean allowed;
		private volatile WebInstrumentationPage page;

		private void start(WebInstrumentationPage nextPage) {
			page = nextPage;
			allowed = true;
		}

		private void clear() {
			allowed = false;
			page = null;
		}

		private boolean isAllowed() {
			return allowed;
		}

		private WebInstrumentationPage currentPage() {
			return page;
		}

		@SuppressWarnings("PMD.CompareObjectsWithEquals") // Pages are intentionally identity tokens.
		private boolean isCurrent(WebInstrumentationPage expectedPage) {
			return allowed && page == expectedPage;
		}
	}

	private static final class WebInstrumentationPage {
		private volatile CfWebInstrumentationRepository.ScriptSnapshot snapshot;
	}

	/**
	 * Observes one CF SDK unique-work stream. Historical completed work is ignored, while a
	 * currently running job starts a refresh. A newly completed job also starts one so the very
	 * short SDK workers cannot lose the refresh if LiveData coalesces RUNNING into SUCCEEDED.
	 */
	static final class SdkSyncRefreshObserver implements Observer<List<WorkInfo>> {
		private final Runnable refresher;
		private final Map<UUID, WorkInfo.State> observedStates = new HashMap<>();
		private final Set<UUID> refreshedWorkIds = new HashSet<>();
		private boolean initialized;

		SdkSyncRefreshObserver(Runnable refresher) {
			this.refresher = refresher;
		}

		@Override
		public void onChanged(List<WorkInfo> workInfos) {
			if (workInfos == null) {
				return;
			}

			boolean shouldRefresh = false;
			for (WorkInfo workInfo : workInfos) {
				if (workInfo == null) {
					continue;
				}
				UUID id = workInfo.getId();
				WorkInfo.State state = workInfo.getState();
				WorkInfo.State previousState = observedStates.put(id, state);
				boolean syncObserved = state == WorkInfo.State.RUNNING ||
					(initialized && state == WorkInfo.State.SUCCEEDED &&
						previousState != WorkInfo.State.SUCCEEDED);
				if (syncObserved && refreshedWorkIds.add(id)) {
					shouldRefresh = true;
				}
			}
			initialized = true;
			if (shouldRefresh) {
				refresher.run();
			}
		}
	}

	interface WebInstrumentationAccess {
		boolean isAllowed();
	}

	public static final class WebInstrumentationBridge {
		private final WebInstrumentationLogger logger;
		private final WebInstrumentationAccess access;

		WebInstrumentationBridge(WebInstrumentationLogger logger) {
			this(logger, () -> true);
		}

		WebInstrumentationBridge(
			WebInstrumentationLogger logger,
			WebInstrumentationAccess access
		) {
			this.logger = logger;
			this.access = access;
		}

		@JavascriptInterface
		public void log(String payload) {
			track(payload);
		}

		@JavascriptInterface
		public void track(String payload) {
			if (!access.isAllowed() ||
					!isPayloadWithinLimit(payload, MAX_TRACK_PAYLOAD_LENGTH, "track")) {
				return;
			}
			logger.log(payload);
		}

		@JavascriptInterface
		public void logUser(String payload) {
			if (!access.isAllowed() ||
					!isPayloadWithinLimit(payload, MAX_USER_PAYLOAD_LENGTH, "user")) {
				return;
			}
			handleUser(payload);
		}

		// TEMP diagnostics: logged to logcat only (CHT_WEB_DEBUG), never ingested as an event.
		@JavascriptInterface
		public void debug(String message) {
			if (access.isAllowed() && DEBUG && message != null) {
				MedicLog.log(
					CfSdkHelper.class,
					"CHT_WEB_DEBUG %s",
					message.substring(0, Math.min(message.length(), MAX_DEBUG_MESSAGE_LENGTH))
				);
			}
		}

		private static boolean isPayloadWithinLimit(String payload, int limit, String kind) {
			if (payload != null && payload.length() <= limit) {
				return true;
			}
			MedicLog.warn(CfSdkHelper.class,
				"Ignoring invalid or oversized CF web instrumentation %s payload", kind);
			return false;
		}
	}

	interface WebInstrumentationLogger {
		void log(String payload);
	}

	public interface WebEventTracker {
		void track(String name, String property, Map<String, Object> meta);
	}

	interface DeviceSyncListener {
		void onStarted();

		void onCompleted();
	}

	private static Optional<CfSdkHelper> fromAuthSession(String encodedSession) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(withBase64Padding(encodedSession));
			int userIdEnd = indexOf(decoded, SESSION_FIELD_DELIMITER, 0);
			int timestampEnd = indexOf(decoded, SESSION_FIELD_DELIMITER, userIdEnd + 1);
			if (userIdEnd <= 0 || timestampEnd <= userIdEnd + 1) {
				return Optional.empty();
			}

			String userId = new String(decoded, 0, userIdEnd, StandardCharsets.UTF_8);
			String timestampHex = new String(decoded, userIdEnd + 1, timestampEnd - userIdEnd - 1, StandardCharsets.UTF_8);
			return Optional.of(new CfSdkHelper(userId, parseHexTimestamp(timestampHex).orElse(null), timestampHex));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private static Optional<Long> parseHexTimestamp(String timestampHex) {
		try {
			return Optional.of(Long.parseLong(timestampHex, 16));
		} catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	private static Optional<CfSdkHelper> fromCouchDbUserId(String cookieHeader) {
		Optional<String> rawUserId = userIdAfterCouchDbPrefix(cookieHeader);
		if (rawUserId.isPresent()) {
			return rawUserId.map(userId -> new CfSdkHelper(userId, null, null));
		}

		return userIdAfterCouchDbPrefix(urlDecode(cookieHeader))
			.map(userId -> new CfSdkHelper(userId, null, null));
	}

	private static Optional<String> userIdAfterCouchDbPrefix(String value) {
		int prefixStart = value.indexOf(COUCHDB_USER_ID_PREFIX);
		if (prefixStart < 0) {
			return Optional.empty();
		}

		int userIdStart = prefixStart + COUCHDB_USER_ID_PREFIX.length();
		int userIdEnd = userIdStart;
		while (userIdEnd < value.length() && isCouchDbUserIdCharacter(value.charAt(userIdEnd))) {
			userIdEnd++;
		}

		if (userIdEnd == userIdStart) {
			return Optional.empty();
		}

		return Optional.of(value.substring(userIdStart, userIdEnd));
	}

	private static boolean isCouchDbUserIdCharacter(char value) {
		return Character.isLetterOrDigit(value) ||
			value == '_' ||
			value == '-' ||
			value == '.' ||
			value == '@';
	}

	private static int indexOf(byte[] values, byte target, int start) {
		for (int index = start; index < values.length; index++) {
			if (values[index] == target) {
				return index;
			}
		}
		return -1;
	}

	private static String withBase64Padding(String value) {
		int padding = (4 - value.length() % 4) % 4;
		StringBuilder padded = new StringBuilder(value);
		for (int index = 0; index < padding; index++) {
			padded.append('=');
		}
		return padded.toString();
	}

	private static String urlDecode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException | IllegalArgumentException ex) {
			return value;
		}
	}

	private static String timestampSecondsToIso8601(long timestampSeconds) {
		SimpleDateFormat formatter = new SimpleDateFormat(UTC_DATE_FORMAT, Locale.US);
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		return formatter.format(new Date(timestampSeconds * 1000L));
	}

	private static void handleWebEvent(Context context, String payload) {
		MedicLog.trace(CfSdkHelper.class, "%s %s", WEB_INSTRUMENTATION_LOG_PREFIX, payload);
		dispatchWebEvent(
			payload,
			CfSdkHelper::ingestTrackEvent,
			new DeviceSyncListener() {
				@Override
				public void onStarted() {
					CfWebInstrumentationRepository.getInstance(context)
						.refreshForDeviceSyncAsync();
				}

				@Override
				public void onCompleted() {
					// Fallback if the start event was missed. After a successful start refresh,
					// the repository schedule makes this check a no-op.
					CfWebInstrumentationRepository.getInstance(context).refreshAsync();
					try {
						// The SDK owns these unique, network-constrained workers. Enqueueing them
						// after this event is stored flushes all queued events and catalogs.
						WorkerCaller.INSTANCE.updateAppEvents(context);
					} catch (RuntimeException exception) {
						warn(exception, "Unable to enqueue CF SDK uploads after CHT sync");
					}
				}
			}
		);
	}

	/**
	 * Builds a CF user catalog from the details gathered web-side (user-settings doc and the
	 * facility place name) and upserts it against the user id. Only enum-safe fields are sent
	 * by the web layer, so the SDK validator does not reject the catalog.
	 */
	private static String lastIdentifiedUserId;

	private static void handleUser(String payload) {
		String subjectId;
		String username;
		UserCatalogModel model = new UserCatalogModel();
		try {
			JSONObject json = new JSONObject(payload);
			subjectId = optString(json, "subject_id");
			if (subjectId == null) {
				return;
			}
			username = optString(json, "username");
			model.setName(optString(json, "name"));
			model.setProfession(optString(json, "profession"));
			model.setWorkplace(optString(json, "workplace"));
			model.setOrganizationId(optString(json, "organization_id"));
			model.setOrganizationName(optString(json, "organization_name"));
			model.setLanguage(optString(json, "language"));
			model.setCountry(optString(json, "country"));
			if (username != null) {
				Map<String, Object> meta = new HashMap<>();
				meta.put("username", username);
				model.setMeta(meta);
			}
		} catch (JSONException ex) {
			MedicLog.warn(ex, "CfSdkHelper :: Ignoring malformed user payload");
			return;
		}

		try {
			synchronized (SDK_EVENT_LOCK) {
				// Identify with the user's uuid once per session; the SDK then tags events with it.
				if (!subjectId.equals(lastIdentifiedUserId)) {
					CFCoreEvent.INSTANCE.logIngest(
						CoreEventType.Identify,
						new IdentifyObject(subjectId, IdentifyAction.Login, "", "", "", null),
						null,
						null
					);
					lastIdentifiedUserId = subjectId;
					MedicLog.log(CfSdkHelper.class, "%s identify %s",
						WEB_INSTRUMENTATION_LOG_PREFIX, subjectId);
				}
				CFCoreEvent.INSTANCE.logCatalog(CoreCatalogType.User, subjectId, model);
			}
		} catch (RuntimeException exception) {
			MedicLog.warn(exception, "CfSdkHelper :: CF SDK rejected user data");
		}
	}

	private static String optString(JSONObject json, String key) {
		if (json.isNull(key)) {
			return null;
		}
		String value = json.optString(key, null);
		if (value == null || value.isEmpty()) {
			return null;
		}
		return value.substring(0, Math.min(value.length(), MAX_USER_FIELD_LENGTH));
	}

	/**
	 * Parses an instrumentation payload emitted by the injected web script and forwards
	 * it to the given tracker. Expected shape:
	 * {@code {"group":"tasks","action":"view","screen":"detail","at":"...","meta":{"id":"...","duration_ms":123}}}
	 *
	 * The CF event property is the screen value when present (the action then moves into
	 * meta), otherwise the action itself (e.g. sync events, which have no screen).
	 */
	static void trackWebEvent(String payload, WebEventTracker tracker) {
		String group;
		String action;
		String screen;
		Map<String, Object> meta = new LinkedHashMap<>();
		try {
			JSONObject json = new JSONObject(payload);
			group = json.optString("group", "");
			action = json.optString("action", "");
			screen = json.isNull("screen") ? "" : json.optString("screen", "");
			JSONObject metaJson = json.optJSONObject("meta");
			if (metaJson != null) {
				Iterator<String> keys = metaJson.keys();
				while (keys.hasNext() && meta.size() < MAX_META_PROPERTIES) {
					String key = keys.next();
					Object value = metaJson.opt(key);
					if (key.length() > MAX_META_KEY_LENGTH ||
							value == null || JSONObject.NULL.equals(value) ||
							value instanceof JSONObject || value instanceof JSONArray) {
						continue;
					}
					if (value instanceof String &&
							((String) value).length() > MAX_META_STRING_LENGTH) {
						value = ((String) value).substring(0, MAX_META_STRING_LENGTH);
					}
					if (value instanceof Double && !Double.isFinite((Double) value)) {
						continue;
					}
					meta.put(key, value);
				}
			}
		} catch (JSONException ex) {
			MedicLog.warn(ex, "CfSdkHelper :: Ignoring malformed web instrumentation payload");
			return;
		}

		if (group.isEmpty() || action.isEmpty() ||
				group.length() > MAX_EVENT_COMPONENT_LENGTH ||
				action.length() > MAX_EVENT_COMPONENT_LENGTH ||
				screen.length() > MAX_EVENT_COMPONENT_LENGTH) {
			return;
		}

		String property = screen.isEmpty() ? action : screen;
		if (!screen.isEmpty()) {
			meta.put("action", action);
		}
		tracker.track(group, property, meta);
	}

	static void dispatchWebEvent(
		String payload,
		WebEventTracker tracker,
		DeviceSyncListener syncListener
	) {
		trackWebEvent(payload, (name, property, meta) -> {
			tracker.track(name, property, meta);
			if (!"sync".equals(name)) {
				return;
			}
			if ("start".equals(property)) {
				syncListener.onStarted();
			} else if ("complete".equals(property)) {
				syncListener.onCompleted();
			}
		});
	}

	private static void ingestTrackEvent(String name, String property, Map<String, Object> meta) {
		if(Objects.equals(name, "app")){
			return;
		}
		try {
			synchronized (SDK_EVENT_LOCK) {
				CFCoreEvent.INSTANCE.logIngest(
					CoreEventType.Track,
					new TrackEventObject(name, property, meta),
					null,
					null
				);
			}
		} catch (RuntimeException exception) {
			MedicLog.warn(exception, "CfSdkHelper :: CF SDK rejected track event %s/%s",
				name, property);
		}
	}
}

/**
 * Owns the remotely updateable WebView instrumentation bundle.
 *
 * <p>A bundled asset is always available. Remote responses are staged in persistent internal
 * storage and are promoted to the last-known-good file only after the WebView reports the bridge
 * contract marker. A failed refresh never removes the active offline copy.</p>
 */
@SuppressWarnings("PMD.SingletonClassReturningNewInstance")
final class CfWebInstrumentationRepository {
	static final int BRIDGE_CONTRACT = 1;
	static final String ASSET_PATH = "cf_sdk/cht-web-instrumentation.js";
	static final String SCRIPT_MARKER =
		"/* CHT CF SDK Web Instrumentation; bridge-contract=1 */";

	private static final String DIRECTORY_NAME = "cf_sdk";
	private static final String ACTIVE_FILE_NAME = "cht-web-instrumentation.active.js";
	private static final String CANDIDATE_FILE_NAME = "cht-web-instrumentation.candidate.js";
	private static final String PREFERENCES_NAME = "cf_sdk_web_instrumentation";
	private static final String PREF_SOURCE_URL = "source_url";
	private static final String PREF_ETAG = "etag";
	private static final String PREF_LAST_MODIFIED = "last_modified";
	private static final String PREF_NEXT_REFRESH = "next_refresh";
	private static final String PREF_REJECTED_HASH = "rejected_hash";
	private static final String PREF_CANDIDATE_ATTEMPT_HASH = "candidate_attempt_hash";
	private static final String PREF_ACTIVE_FAILURE_HASH = "active_failure_hash";
	private static final String PREF_ACTIVE_FAILURE_COUNT = "active_failure_count";
	private static final String PREF_HIGHEST_VERSION = "highest_version";
	private static final int ACTIVE_FAILURE_LIMIT = 2;
	private static final int MAX_SCRIPT_BYTES = 256 * 1024;
	private static final int MIN_SCRIPT_BYTES = 256;
	private static final long MIN_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1000L;
	private static final long DEFAULT_REFRESH_INTERVAL_MILLIS = 60 * 60 * 1000L;
	private static final long MAX_REFRESH_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L;
	private static final long FAILURE_RETRY_MILLIS = 15 * 60 * 1000L;
	private static final Pattern MAX_AGE_PATTERN = Pattern.compile(
		"(?:^|,)\\s*max-age\\s*=\\s*([0-9]+)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern VERSION_PATTERN = Pattern.compile(
		"\\bconst\\s+VERSION\\s*=\\s*([1-9][0-9]*)\\s*;"
	);
	private static final Executor DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
	private static volatile CfWebInstrumentationRepository instance;

	private final File activeFile;
	private final File candidateFile;
	private final String bundledScript;
	private final String remoteUrl;
	private final SharedPreferences preferences;
	private final RemoteScriptFetcher fetcher;
	private final Executor executor;
	private final Clock clock;
	private final AtomicBoolean refreshInProgress = new AtomicBoolean();
	private volatile ScriptSnapshot activeScript;
	private volatile ScriptSnapshot candidateScript;
	private volatile long nextRefreshAtMillis;
	private int highestAcceptedVersion;

	static CfWebInstrumentationRepository getInstance(Context context) {
		CfWebInstrumentationRepository current = instance;
		if (current != null) {
			return current;
		}

		synchronized (CfWebInstrumentationRepository.class) {
			current = instance;
			if (current == null) {
				Context applicationContext = context.getApplicationContext();
				if (applicationContext == null) {
					applicationContext = context;
				}
				String bundled = loadBundledScript(applicationContext);
				File directory = new File(applicationContext.getFilesDir(), DIRECTORY_NAME);
				current = new CfWebInstrumentationRepository(
					directory,
					bundled,
					CfSdkHelper.WEB_INSTRUMENTATION_REMOTE_URL,
					applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
					new HttpsRemoteScriptFetcher(),
					DOWNLOAD_EXECUTOR,
					System::currentTimeMillis
				);
				instance = current;
			}
		}
		return current;
	}

	CfWebInstrumentationRepository(
		File directory,
		String bundledScript,
		String remoteUrl,
		SharedPreferences preferences,
		RemoteScriptFetcher fetcher,
		Executor executor,
		Clock clock
	) {
		this.activeFile = new File(directory, ACTIVE_FILE_NAME);
		this.candidateFile = new File(directory, CANDIDATE_FILE_NAME);
		this.bundledScript = requireValidBundledScript(bundledScript);
		this.remoteUrl = normalizeRemoteUrl(remoteUrl);
		this.preferences = preferences;
		this.fetcher = fetcher;
		this.executor = executor;
		this.clock = clock;
		this.activeScript = new ScriptSnapshot(
			ScriptSource.BUNDLED,
			this.bundledScript,
			sha256(this.bundledScript),
			scriptVersion(this.bundledScript)
		);
		this.highestAcceptedVersion = Math.max(
			this.activeScript.version,
			preferences.getInt(PREF_HIGHEST_VERSION, 0)
		);
		preferences.edit().putInt(PREF_HIGHEST_VERSION, highestAcceptedVersion).apply();
		loadStoredScripts();
		this.nextRefreshAtMillis = this.remoteUrl != null && this.remoteUrl.equals(
			preferences.getString(PREF_SOURCE_URL, null)
		)
			? preferences.getLong(PREF_NEXT_REFRESH, 0L)
			: 0L;
	}

	ScriptSnapshot scriptForInjection() {
		// A fresh CDN candidate wins; activeScript is the cached remote copy or bundled fallback.
		ScriptSnapshot candidate = candidateScript;
		return candidate == null ? activeScript : candidate;
	}

	String readinessProbe(ScriptSnapshot snapshot) {
		return "(function(){var i=window.__cfSdkChtInstrumentation;" +
			"return !!(i&&i.bridgeContract===" + BRIDGE_CONTRACT +
			"&&i.version===" + snapshot.version +
			"&&typeof i.send==='function'&&typeof i.route==='function');})()";
	}

	@SuppressWarnings("PMD.CompareObjectsWithEquals") // Only the current snapshot may be marked.
	@SuppressLint("ApplySharedPref") // Must survive a renderer/process crash caused by the candidate.
	synchronized void markCandidateEvaluationStarted(ScriptSnapshot snapshot) {
		if (snapshot == candidateScript && !snapshot.sha256.equals(
			preferences.getString(PREF_CANDIDATE_ATTEMPT_HASH, null)
		)) {
			// Commit before evaluating untrusted remote code so an interrupted renderer/process
			// falls back instead of retrying the same unproven candidate forever.
			preferences.edit()
				.putString(PREF_CANDIDATE_ATTEMPT_HASH, snapshot.sha256)
				.commit();
		}
	}

	void refreshAsync() {
		refreshAsync(false);
	}

	void refreshForDeviceSyncAsync() {
		refreshAsync(true);
	}

	private void refreshAsync(boolean ignoreSchedule) {
		if (remoteUrl == null ||
				(!ignoreSchedule && clock.currentTimeMillis() < nextRefreshAtMillis)) {
			return;
		}
		if (!refreshInProgress.compareAndSet(false, true)) {
			return;
		}

		nextRefreshAtMillis = clock.currentTimeMillis() + FAILURE_RETRY_MILLIS;
		try {
			executor.execute(() -> {
				try {
					refreshBlocking();
				} finally {
					refreshInProgress.set(false);
				}
			});
		} catch (RuntimeException exception) {
			refreshInProgress.set(false);
			scheduleNextRefresh(FAILURE_RETRY_MILLIS);
			warn(exception, "Unable to schedule CF web instrumentation refresh");
		}
	}

	RefreshResult refreshBlocking() {
		if (remoteUrl == null) {
			return RefreshResult.DISABLED;
		}

		boolean sameSource = remoteUrl.equals(preferences.getString(PREF_SOURCE_URL, null));
		String etag = sameSource ? preferences.getString(PREF_ETAG, null) : null;
		long lastModified = sameSource ? preferences.getLong(PREF_LAST_MODIFIED, 0L) : 0L;
		try {
			RemoteResponse response = fetcher.fetch(remoteUrl, etag, lastModified);
			return applyRemoteResponse(response, sameSource, etag, lastModified);
		} catch (IOException | RuntimeException exception) {
			warn(exception, "CF web instrumentation refresh failed; keeping last-known-good script");
			scheduleNextRefresh(FAILURE_RETRY_MILLIS);
			return RefreshResult.FAILED;
		}
	}

	/**
	 * Applies a completed network response while holding the same monitor as readiness callbacks.
	 * The fetch itself deliberately stays outside this critical section so a slow CDN cannot block
	 * a WebView callback from promoting or rejecting the candidate it already evaluated.
	 */
	private synchronized RefreshResult applyRemoteResponse(
		RemoteResponse response,
		boolean sameSource,
		String previousEtag,
		long previousLastModified
	) throws IOException {
		if (response.notModified) {
			if (!sameSource || !hasStoredRemoteScript()) {
				clearValidators();
				scheduleNextRefresh(FAILURE_RETRY_MILLIS);
				return RefreshResult.FAILED;
			}
			updateResponseMetadata(response, previousEtag, previousLastModified);
			scheduleNextRefresh(response.maxAgeMillis);
			return RefreshResult.NOT_MODIFIED;
		}

		String downloadedScript = response.script;
		if (!isValidScript(downloadedScript)) {
			discardPendingCandidate();
			warn(CfWebInstrumentationRepository.class,
				"CF web instrumentation refresh rejected an invalid script");
			scheduleNextRefresh(FAILURE_RETRY_MILLIS);
			return RefreshResult.REJECTED;
		}

		String hash = sha256(downloadedScript);
		int version = scriptVersion(downloadedScript);
		ScriptSnapshot currentCandidate = candidateScript;
		if (currentCandidate != null && !hash.equals(currentCandidate.sha256)) {
			discardPendingCandidate();
			currentCandidate = null;
		}
		String rejectedHash = preferences.getString(PREF_REJECTED_HASH, null);
		if (hash.equals(rejectedHash)) {
			updateResponseMetadata(response, previousEtag, previousLastModified);
			scheduleNextRefresh(response.maxAgeMillis);
			return RefreshResult.REJECTED;
		}

		if (hash.equals(activeScript.sha256)) {
			updateResponseMetadata(response, previousEtag, previousLastModified);
			scheduleNextRefresh(response.maxAgeMillis);
			return RefreshResult.NOT_MODIFIED;
		}
		if (version <= highestAcceptedVersion) {
			warn(CfWebInstrumentationRepository.class,
				"Rejected CF web instrumentation version %s; highest accepted version is %s",
				version, highestAcceptedVersion);
			updateResponseMetadata(response, previousEtag, previousLastModified);
			scheduleNextRefresh(response.maxAgeMillis);
			return RefreshResult.REJECTED;
		}
		if (currentCandidate != null && hash.equals(currentCandidate.sha256)) {
			updateResponseMetadata(response, previousEtag, previousLastModified);
			scheduleNextRefresh(response.maxAgeMillis);
			return RefreshResult.NOT_MODIFIED;
		}

		writeAtomically(candidateFile, downloadedScript);
		candidateScript = new ScriptSnapshot(
			ScriptSource.REMOTE_CANDIDATE,
			downloadedScript,
			hash,
			version
		);
		preferences.edit().remove(PREF_CANDIDATE_ATTEMPT_HASH).apply();
		updateResponseMetadata(response, previousEtag, previousLastModified);
		scheduleNextRefresh(response.maxAgeMillis);
		log(CfWebInstrumentationRepository.class,
			"Staged CF web instrumentation update %s", hash.substring(0, 12));
		return RefreshResult.STAGED;
	}

	@SuppressWarnings("PMD.CompareObjectsWithEquals") // Snapshot identity prevents stale callbacks from changing state.
	synchronized void recordInjectionResult(ScriptSnapshot snapshot, boolean ready) {
		if (snapshot == null) {
			return;
		}

		if (snapshot == candidateScript) {
			if (ready) {
				promoteCandidate(snapshot);
			} else {
				rejectCandidate(snapshot);
			}
			return;
		}

		if (snapshot == activeScript && snapshot.source == ScriptSource.REMOTE_ACTIVE) {
			recordActiveReadiness(snapshot, ready);
		}
	}

	private void recordActiveReadiness(ScriptSnapshot snapshot, boolean ready) {
		String failedHash = preferences.getString(PREF_ACTIVE_FAILURE_HASH, null);
		if (ready) {
			if (snapshot.sha256.equals(failedHash)) {
				preferences.edit()
					.remove(PREF_ACTIVE_FAILURE_HASH)
					.remove(PREF_ACTIVE_FAILURE_COUNT)
					.apply();
			}
			return;
		}

		int failures = snapshot.sha256.equals(failedHash)
			? preferences.getInt(PREF_ACTIVE_FAILURE_COUNT, 0) + 1
			: 1;
		if (failures < ACTIVE_FAILURE_LIMIT) {
			preferences.edit()
				.putString(PREF_ACTIVE_FAILURE_HASH, snapshot.sha256)
				.putInt(PREF_ACTIVE_FAILURE_COUNT, failures)
				.apply();
			warn(CfWebInstrumentationRepository.class,
				"CF web instrumentation active script did not answer its readiness probe");
			return;
		}

		new AtomicFile(activeFile).delete();
		activeScript = bundledSnapshot();
		nextRefreshAtMillis = 0L;
		preferences.edit()
			.putString(PREF_REJECTED_HASH, snapshot.sha256)
			.remove(PREF_ACTIVE_FAILURE_HASH)
			.remove(PREF_ACTIVE_FAILURE_COUNT)
			.remove(PREF_ETAG)
			.remove(PREF_LAST_MODIFIED)
			.remove(PREF_SOURCE_URL)
			.remove(PREF_NEXT_REFRESH)
			.apply();
		warn(CfWebInstrumentationRepository.class,
			"Quarantined CF web instrumentation active script %s after repeated failures",
			snapshot.sha256.substring(0, 12));
	}

	private ScriptSnapshot bundledSnapshot() {
		return new ScriptSnapshot(
			ScriptSource.BUNDLED,
			bundledScript,
			sha256(bundledScript),
			scriptVersion(bundledScript)
		);
	}

	private void promoteCandidate(ScriptSnapshot snapshot) {
		try {
			writeAtomically(activeFile, snapshot.script);
			new AtomicFile(candidateFile).delete();
			activeScript = new ScriptSnapshot(
				ScriptSource.REMOTE_ACTIVE,
				snapshot.script,
				snapshot.sha256,
				snapshot.version
			);
			highestAcceptedVersion = Math.max(highestAcceptedVersion, snapshot.version);
			candidateScript = null;
			preferences.edit()
				.putInt(PREF_HIGHEST_VERSION, highestAcceptedVersion)
				.remove(PREF_REJECTED_HASH)
				.remove(PREF_CANDIDATE_ATTEMPT_HASH)
				.remove(PREF_ACTIVE_FAILURE_HASH)
				.remove(PREF_ACTIVE_FAILURE_COUNT)
				.apply();
			log(CfWebInstrumentationRepository.class,
				"Activated CF web instrumentation update %s", snapshot.sha256.substring(0, 12));
		} catch (IOException exception) {
			warn(exception,
				"CF web instrumentation candidate passed but could not be activated");
		}
	}

	private void rejectCandidate(ScriptSnapshot snapshot) {
		new AtomicFile(candidateFile).delete();
		candidateScript = null;
		preferences.edit()
			.putString(PREF_REJECTED_HASH, snapshot.sha256)
			.remove(PREF_CANDIDATE_ATTEMPT_HASH)
			.apply();
		warn(CfWebInstrumentationRepository.class,
			"Rejected CF web instrumentation candidate %s after readiness probe",
			snapshot.sha256.substring(0, 12));
	}

	private void discardPendingCandidate() {
		ScriptSnapshot discarded = candidateScript;
		if (discarded == null) {
			return;
		}
		new AtomicFile(candidateFile).delete();
		candidateScript = null;
		preferences.edit().remove(PREF_CANDIDATE_ATTEMPT_HASH).apply();
		log(CfWebInstrumentationRepository.class,
			"Discarded superseded CF web instrumentation candidate %s",
			discarded.sha256.substring(0, 12));
	}

	private void loadStoredScripts() {
		if (remoteUrl == null) {
			return;
		}
		if (!remoteUrl.equals(preferences.getString(PREF_SOURCE_URL, null))) {
			new AtomicFile(activeFile).delete();
			new AtomicFile(candidateFile).delete();
			return;
		}

		String storedActive = readStoredScript(activeFile);
		if (storedActive != null &&
				scriptVersion(storedActive) > activeScript.version &&
				scriptVersion(storedActive) >= highestAcceptedVersion) {
			activeScript = new ScriptSnapshot(
				ScriptSource.REMOTE_ACTIVE,
				storedActive,
				sha256(storedActive),
				scriptVersion(storedActive)
			);
			highestAcceptedVersion = Math.max(highestAcceptedVersion, activeScript.version);
			preferences.edit().putInt(PREF_HIGHEST_VERSION, highestAcceptedVersion).apply();
		} else if (storedActive != null) {
			new AtomicFile(activeFile).delete();
		}

		String storedCandidate = readStoredScript(candidateFile);
		if (storedCandidate != null &&
				scriptVersion(storedCandidate) > highestAcceptedVersion) {
			String storedCandidateHash = sha256(storedCandidate);
			if (storedCandidateHash.equals(
				preferences.getString(PREF_CANDIDATE_ATTEMPT_HASH, null)
			)) {
				new AtomicFile(candidateFile).delete();
				preferences.edit().remove(PREF_CANDIDATE_ATTEMPT_HASH).apply();
				clearValidators();
				warn(CfWebInstrumentationRepository.class,
					"Discarded an interrupted CF web instrumentation candidate");
			} else {
				candidateScript = new ScriptSnapshot(
					ScriptSource.REMOTE_CANDIDATE,
					storedCandidate,
					storedCandidateHash,
					scriptVersion(storedCandidate)
				);
			}
		} else if (storedCandidate != null) {
			new AtomicFile(candidateFile).delete();
			preferences.edit().remove(PREF_CANDIDATE_ATTEMPT_HASH).apply();
		}
	}

	private String readStoredScript(File file) {
		AtomicFile atomicFile = new AtomicFile(file);
		if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
			return null;
		}
		try (InputStream input = atomicFile.openRead()) {
			String script = readUtf8(input, MAX_SCRIPT_BYTES);
			if (isValidScript(script)) {
				return script;
			}
			warn(CfWebInstrumentationRepository.class,
				"Ignoring invalid stored CF web instrumentation file %s", file.getName());
		} catch (IOException exception) {
			warn(exception, "Unable to read stored CF web instrumentation file %s", file.getName());
		}
		atomicFile.delete();
		return null;
	}

	private boolean hasStoredRemoteScript() {
		return candidateScript != null || activeScript.source == ScriptSource.REMOTE_ACTIVE;
	}

	private void updateResponseMetadata(
		RemoteResponse response,
		String previousEtag,
		long previousLastModified
	) {
		SharedPreferences.Editor editor = preferences.edit()
			.putString(PREF_SOURCE_URL, remoteUrl);
		long responseLastModified = response.lastModified > 0L
			? response.lastModified
			: (response.notModified ? previousLastModified : 0L);
		if (responseLastModified > 0L) {
			editor.putLong(PREF_LAST_MODIFIED, responseLastModified);
		} else {
			editor.remove(PREF_LAST_MODIFIED);
		}
		String responseEtag = response.etag == null && response.notModified
			? previousEtag
			: response.etag;
		if (responseEtag == null) {
			editor.remove(PREF_ETAG);
		} else {
			editor.putString(PREF_ETAG, responseEtag);
		}
		editor.apply();
	}

	private void clearValidators() {
		preferences.edit()
			.remove(PREF_ETAG)
			.remove(PREF_LAST_MODIFIED)
			.remove(PREF_SOURCE_URL)
			.apply();
	}

	private void scheduleNextRefresh(long requestedIntervalMillis) {
		long interval = requestedIntervalMillis < 0L
			? DEFAULT_REFRESH_INTERVAL_MILLIS
			: requestedIntervalMillis;
		interval = Math.max(MIN_REFRESH_INTERVAL_MILLIS,
			Math.min(interval, MAX_REFRESH_INTERVAL_MILLIS));
		nextRefreshAtMillis = clock.currentTimeMillis() + interval;
		preferences.edit().putLong(PREF_NEXT_REFRESH, nextRefreshAtMillis).apply();
	}

	private static String loadBundledScript(Context context) {
		try (InputStream input = context.getAssets().open(ASSET_PATH)) {
			return readUtf8(input, MAX_SCRIPT_BYTES);
		} catch (IOException exception) {
			throw new IllegalStateException("Bundled CF web instrumentation is unavailable", exception);
		}
	}

	private static String requireValidBundledScript(String script) {
		if (!isValidScript(script)) {
			throw new IllegalStateException("Bundled CF web instrumentation is invalid");
		}
		return script;
	}

	static boolean isValidScript(String script) {
		if (script == null || script.length() < MIN_SCRIPT_BYTES) {
			return false;
		}
		byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
		return bytes.length <= MAX_SCRIPT_BYTES &&
			script.startsWith(SCRIPT_MARKER) &&
			script.contains("'cf_sdk_android'") &&
			script.contains("BRIDGE_CONTRACT = 1") &&
			script.contains("window.__cfSdkChtInstrumentation") &&
			script.contains("version: VERSION") &&
			script.contains("bridgeContract: BRIDGE_CONTRACT") &&
			script.contains("send: send") &&
			script.contains("route: route") &&
			scriptVersion(script) > 0;
	}

	private static int scriptVersion(String script) {
		if (script == null) {
			return -1;
		}
		Matcher matcher = VERSION_PATTERN.matcher(script);
		if (!matcher.find()) {
			return -1;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private static String normalizeRemoteUrl(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		try {
			URL url = new URL(normalized);
			if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) {
				warn(CfWebInstrumentationRepository.class,
					"CF web instrumentation URL must use HTTPS; remote updates are disabled");
				return null;
			}
			return normalized;
		} catch (IOException exception) {
			warn(exception, "CF web instrumentation URL is invalid; remote updates are disabled");
			return null;
		}
	}

	@SuppressWarnings("PMD.CloseResource") // AtomicFile closes the stream in finishWrite/failWrite.
	private static void writeAtomically(File file, String value) throws IOException {
		File parent = file.getParentFile();
		if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
			throw new IOException("Unable to create CF web instrumentation directory");
		}

		AtomicFile atomicFile = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomicFile.startWrite();
			output.write(value.getBytes(StandardCharsets.UTF_8));
			atomicFile.finishWrite(output);
		} catch (IOException exception) {
			if (output != null) {
				atomicFile.failWrite(output);
			}
			throw exception;
		}
	}

	private static String readUtf8(InputStream input, int maximumBytes) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = input.read(buffer)) != -1) {
			total += read;
			if (total > maximumBytes) {
				throw new IOException("CF web instrumentation exceeds the size limit");
			}
			output.write(buffer, 0, read);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(output.toByteArray()))
				.toString();
		} catch (CharacterCodingException exception) {
			throw new IOException("CF web instrumentation is not valid UTF-8", exception);
		}
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(digest.length * 2);
			for (byte item : digest) {
				result.append(String.format(Locale.US, "%02x", item & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	enum RefreshResult {
		DISABLED,
		STAGED,
		NOT_MODIFIED,
		REJECTED,
		FAILED
	}

	enum ScriptSource {
		BUNDLED,
		REMOTE_ACTIVE,
		REMOTE_CANDIDATE
	}

	static final class ScriptSnapshot {
		final ScriptSource source;
		final String script;
		final String sha256;
		final int version;

		ScriptSnapshot(ScriptSource source, String script, String sha256, int version) {
			this.source = source;
			this.script = script;
			this.sha256 = sha256;
			this.version = version;
		}
	}

	interface Clock {
		long currentTimeMillis();
	}

	interface RemoteScriptFetcher {
		RemoteResponse fetch(String url, String etag, long lastModified) throws IOException;
	}

	static final class RemoteResponse {
		final boolean notModified;
		final String script;
		final String etag;
		final long lastModified;
		final long maxAgeMillis;

		private RemoteResponse(
			boolean notModified,
			String script,
			String etag,
			long lastModified,
			long maxAgeMillis
		) {
			this.notModified = notModified;
			this.script = script;
			this.etag = etag;
			this.lastModified = lastModified;
			this.maxAgeMillis = maxAgeMillis;
		}

		static RemoteResponse downloaded(
			String script,
			String etag,
			long lastModified,
			long maxAgeMillis
		) {
			return new RemoteResponse(false, script, etag, lastModified, maxAgeMillis);
		}

		static RemoteResponse notModified(
			String etag,
			long lastModified,
			long maxAgeMillis
		) {
			return new RemoteResponse(true, null, etag, lastModified, maxAgeMillis);
		}
	}

	static final class HttpsRemoteScriptFetcher implements RemoteScriptFetcher {
		private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
		private static final int READ_TIMEOUT_MILLIS = 15_000;

		@Override
		public RemoteResponse fetch(String value, String etag, long lastModified)
				throws IOException {
			URL url = new URL(value);
			if (!"https".equalsIgnoreCase(url.getProtocol())) {
				throw new IOException("CF web instrumentation URL must use HTTPS");
			}

			HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
			try {
				connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
				connection.setReadTimeout(READ_TIMEOUT_MILLIS);
				connection.setInstanceFollowRedirects(false);
				connection.setUseCaches(false);
				connection.setRequestProperty("Accept", "application/javascript, text/javascript, text/plain");
				connection.setRequestProperty("Accept-Encoding", "identity");
				connection.setRequestProperty(
					"User-Agent",
					"CHT-Android-CF-Web-Instrumentation/" + BuildConfig.VERSION_NAME
				);
				if (etag != null && !etag.isEmpty()) {
					connection.setRequestProperty("If-None-Match", etag);
				}
				if (lastModified > 0L) {
					connection.setIfModifiedSince(lastModified);
				}

				int status = connection.getResponseCode();
				if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
					throw new IOException("CF web instrumentation redirect left HTTPS");
				}
				String responseEtag = connection.getHeaderField("ETag");
				long responseLastModified = connection.getLastModified();
				long maxAgeMillis = parseMaxAgeMillis(connection.getHeaderField("Cache-Control"));
				if (status == HttpsURLConnection.HTTP_NOT_MODIFIED) {
					return RemoteResponse.notModified(
						responseEtag,
						responseLastModified,
						maxAgeMillis
					);
				}
				if (status >= 300 && status < 400) {
					throw new IOException("CF web instrumentation redirects are not allowed");
				}
				if (status != HttpsURLConnection.HTTP_OK) {
					throw new IOException("CF web instrumentation request returned HTTP " + status);
				}
				int contentLength = connection.getContentLength();
				if (contentLength > MAX_SCRIPT_BYTES) {
					throw new IOException("CF web instrumentation exceeds the size limit");
				}
				try (InputStream input = connection.getInputStream()) {
					return RemoteResponse.downloaded(
						readUtf8(input, MAX_SCRIPT_BYTES),
						responseEtag,
						responseLastModified,
						maxAgeMillis
					);
				}
			} finally {
				connection.disconnect();
			}
		}

		private static long parseMaxAgeMillis(String cacheControl) {
			if (cacheControl == null) {
				return DEFAULT_REFRESH_INTERVAL_MILLIS;
			}
			Matcher matcher = MAX_AGE_PATTERN.matcher(cacheControl);
			if (!matcher.find()) {
				return DEFAULT_REFRESH_INTERVAL_MILLIS;
			}
			try {
				long seconds = Long.parseLong(matcher.group(1));
				return Math.multiplyExact(seconds, 1000L);
			} catch (NumberFormatException | ArithmeticException exception) {
				return DEFAULT_REFRESH_INTERVAL_MILLIS;
			}
		}
	}
}
