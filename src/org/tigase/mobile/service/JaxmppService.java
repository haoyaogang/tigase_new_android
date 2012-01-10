package org.tigase.mobile.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tigase.mobile.Constants;
import org.tigase.mobile.MessengerApplication;
import org.tigase.mobile.Preferences;
import org.tigase.mobile.R;
import org.tigase.mobile.RosterDisplayTools;
import org.tigase.mobile.TigaseMobileMessengerActivity;
import org.tigase.mobile.db.AccountsTableMetaData;
import org.tigase.mobile.db.ChatTableMetaData;
import org.tigase.mobile.db.VCardsCacheTableMetaData;
import org.tigase.mobile.db.providers.AccountsProvider;
import org.tigase.mobile.db.providers.ChatHistoryProvider;
import org.tigase.mobile.db.providers.RosterProvider;
import org.tigase.mobile.roster.AuthRequestActivity;

import tigase.jaxmpp.core.client.BareJID;
import tigase.jaxmpp.core.client.Base64;
import tigase.jaxmpp.core.client.Connector;
import tigase.jaxmpp.core.client.Connector.ConnectorEvent;
import tigase.jaxmpp.core.client.Connector.State;
import tigase.jaxmpp.core.client.DefaultSessionObject;
import tigase.jaxmpp.core.client.JID;
import tigase.jaxmpp.core.client.JaxmppCore;
import tigase.jaxmpp.core.client.MultiJaxmpp;
import tigase.jaxmpp.core.client.SessionObject;
import tigase.jaxmpp.core.client.XMPPException.ErrorCondition;
import tigase.jaxmpp.core.client.connector.StreamError;
import tigase.jaxmpp.core.client.exceptions.JaxmppException;
import tigase.jaxmpp.core.client.observer.Listener;
import tigase.jaxmpp.core.client.xml.Element;
import tigase.jaxmpp.core.client.xml.XMLException;
import tigase.jaxmpp.core.client.xmpp.modules.ResourceBinderModule;
import tigase.jaxmpp.core.client.xmpp.modules.ResourceBinderModule.ResourceBindEvent;
import tigase.jaxmpp.core.client.xmpp.modules.SoftwareVersionModule;
import tigase.jaxmpp.core.client.xmpp.modules.auth.AuthModule;
import tigase.jaxmpp.core.client.xmpp.modules.auth.AuthModule.AuthEvent;
import tigase.jaxmpp.core.client.xmpp.modules.chat.MessageModule;
import tigase.jaxmpp.core.client.xmpp.modules.chat.MessageModule.MessageEvent;
import tigase.jaxmpp.core.client.xmpp.modules.presence.PresenceModule;
import tigase.jaxmpp.core.client.xmpp.modules.presence.PresenceModule.PresenceEvent;
import tigase.jaxmpp.core.client.xmpp.modules.roster.RosterItem;
import tigase.jaxmpp.core.client.xmpp.modules.roster.RosterModule;
import tigase.jaxmpp.core.client.xmpp.modules.roster.RosterModule.RosterEvent;
import tigase.jaxmpp.core.client.xmpp.modules.vcard.VCard;
import tigase.jaxmpp.core.client.xmpp.modules.vcard.VCardModule;
import tigase.jaxmpp.core.client.xmpp.modules.vcard.VCardModule.VCardAsyncCallback;
import tigase.jaxmpp.core.client.xmpp.stanzas.Message;
import tigase.jaxmpp.core.client.xmpp.stanzas.Presence.Show;
import tigase.jaxmpp.core.client.xmpp.stanzas.Stanza;
import tigase.jaxmpp.core.client.xmpp.stanzas.StanzaType;
import tigase.jaxmpp.j2se.Jaxmpp;
import tigase.jaxmpp.j2se.connectors.socket.SocketConnector;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

public class JaxmppService extends Service {

	private class ClientFocusReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			final int page = intent.getIntExtra("page", -1);
			final long chatId = intent.getLongExtra("chatId", -1);

			onPageChanged(page);

			currentChatIdFocus = chatId;

			notificationManager.cancel("chatId:" + chatId, CHAT_NOTIFICATION_ID);
		}
	}

	private class ConnReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			NetworkInfo netInfo = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
			onNetworkChanged(netInfo);
		}

	}

	private static enum NotificationVariant {
		always,
		none,
		only_connected,
		only_disconnected
	}

	public static final int AUTH_REQUEST_NOTIFICATION_ID = 132108;

	public static final int CHAT_NOTIFICATION_ID = 132008;

	private static final boolean DEBUG = true;

	public static final int NOTIFICATION_ID = 5398777;

	private static boolean serviceActive = false;

	private static final String TAG = "tigase";

	private static Date calculateNextRestart(final int delayInSecs, final int errorCounter) {
		long timeInSecs = delayInSecs;
		if (errorCounter > 20) {
			timeInSecs += 60 * 5;
		} else if (errorCounter > 10) {
			timeInSecs += 120;
		} else if (errorCounter > 5) {
			timeInSecs += 60;
		}

		Date d = new Date((new Date()).getTime() + 1000 * timeInSecs);
		return d;
	}

	private static void disable(SessionObject jaxmpp, boolean disabled) {
		jaxmpp.setProperty("CC:DISABLED", disabled);
	}

	private static Throwable extractCauseException(Exception ex) {
		Throwable th = ex.getCause();
		if (th == null)
			return ex;

		for (int i = 0; i < 4; i++) {
			if (!(th instanceof JaxmppException))
				return th;
			if (th.getCause() == null)
				return th;
			th = th.getCause();
		}
		return ex;
	}

	private static boolean isDisabled(SessionObject jaxmpp) {
		Boolean x = jaxmpp.getProperty("CC:DISABLED");
		return x == null ? false : x;
	}

	private static boolean isLocked(SessionObject jaxmpp) {
		Boolean x = jaxmpp.getProperty("CC:LOCKED");
		return x == null ? false : x;
	}

	public static boolean isServiceActive() {
		return serviceActive;
	}

	private static void lock(SessionObject jaxmpp, boolean locked) {
		jaxmpp.setProperty("CC:LOCKED", locked);
	}
	
	public static void updateJaxmppInstances(MultiJaxmpp multi, ContentResolver contentResolver, Resources resources, Context context) {

		final HashSet<JID> accountsJids = new HashSet<JID>();
		for (JaxmppCore jc : multi.get()) {
			accountsJids.add(jc.getSessionObject().getUserJid());
		}
//		final Cursor c = contentResolver.query(Uri.parse(AccountsProvider.ACCOUNTS_LIST_KEY), null, null, null, null);
		try {
//			while (c.moveToNext()) {
			AccountManager accountManager = AccountManager.get(context);
			for (Account account : accountManager.getAccountsByType(Constants.ACCOUNT_TYPE)) {
//				long id = c.getLong(c.getColumnIndex(AccountsTableMetaData.FIELD_ID));
//				JID jid = JID.jidInstance(c.getString(c.getColumnIndex(AccountsTableMetaData.FIELD_JID)));
//				String password = c.getString(c.getColumnIndex(AccountsTableMetaData.FIELD_PASSWORD));
//				String nickname = c.getString(c.getColumnIndex(AccountsTableMetaData.FIELD_NICKNAME));
//				String hostname = c.getString(c.getColumnIndex(AccountsTableMetaData.FIELD_HOSTNAME));
				JID jid = JID.jidInstance(account.name);
				String password = accountManager.getPassword(account);
				String nickname = accountManager.getUserData(account, AccountsTableMetaData.FIELD_NICKNAME);
				String hostname = accountManager.getUserData(account, AccountsTableMetaData.FIELD_HOSTNAME);

				if (!accountsJids.contains(jid)) {
					SessionObject sessionObject = new DefaultSessionObject();
					sessionObject.setUserProperty(SoftwareVersionModule.VERSION_KEY, resources.getString(R.string.app_version));
					sessionObject.setUserProperty(SoftwareVersionModule.NAME_KEY, "Tigase Mobile Messenger");
					sessionObject.setUserProperty(SoftwareVersionModule.OS_KEY, "Android " + android.os.Build.VERSION.RELEASE);
					sessionObject.setUserProperty("ID", (long) account.hashCode());
					sessionObject.setUserProperty(SocketConnector.SERVER_PORT, 5222);
					sessionObject.setUserProperty(Jaxmpp.CONNECTOR_TYPE, "socket");
					sessionObject.setUserProperty(SessionObject.USER_JID, JID.jidInstance(jid.getBareJid()));
					sessionObject.setUserProperty(SessionObject.PASSWORD, password);
					sessionObject.setUserProperty(SessionObject.NICKNAME, nickname);
					if (hostname != null && hostname.trim().length() > 0)
						sessionObject.setUserProperty(SocketConnector.SERVER_HOST, hostname);
					else
						sessionObject.setUserProperty(SocketConnector.SERVER_HOST, null);

					if (jid.getResource() != null)
						sessionObject.setUserProperty(SessionObject.RESOURCE, jid.getResource());
					else
						sessionObject.setUserProperty(SessionObject.RESOURCE, null);

					final Jaxmpp jaxmpp = new Jaxmpp(sessionObject);
					multi.add(jaxmpp);
				}
				accountsJids.remove(jid);
			}
		} finally {
			//c.close();
		}
		for (JID jid : accountsJids) {
			JaxmppCore jaxmpp = multi.get(jid.getBareJid());
			if (jaxmpp != null)
				multi.remove(jaxmpp);
		}
	}

	private TimerTask autoPresenceTask;

	private int connectionErrorCounter = 0;

	private Listener<ConnectorEvent> connectorListener;

	private ConnectivityManager connManager;

	private long currentChatIdFocus = -1;

	private ClientFocusReceiver focusChangeReceiver;

	private boolean focused;

	private Listener<AuthEvent> invalidAuthListener;

	private final Listener<MessageModule.MessageEvent> messageListener;

	private ConnReceiver myConnReceiver;

	private NotificationManager notificationManager;

	private NotificationVariant notificationVariant = NotificationVariant.always;

	private OnSharedPreferenceChangeListener prefChangeListener;

	private SharedPreferences prefs;

	private final Listener<PresenceModule.PresenceEvent> presenceListener;

	private final Listener<PresenceEvent> presenceSendListener;

	private boolean reconnect = true;

	private final Listener<ResourceBindEvent> resourceBindListener;

	private final Listener<RosterModule.RosterEvent> rosterListener;

	private final Listener<Connector.ConnectorEvent> stateChangeListener;

	private Listener<PresenceEvent> subscribeRequestListener;

	private final Timer timer = new Timer();

	private int usedNetworkType = -1;

	private String userStatusMessage = null;

	private Show userStatusShow = Show.online;

	public JaxmppService() {
		super();
		Logger logger = Logger.getLogger("tigase.jaxmpp");
		// create a ConsoleHandler
		Handler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		logger.addHandler(handler);
		logger.setLevel(Level.ALL);

		if (DEBUG)
			Log.i(TAG, "creating");

		this.presenceSendListener = new Listener<PresenceModule.PresenceEvent>() {

			@Override
			public void handleEvent(PresenceEvent be) throws JaxmppException {
				be.setStatus(userStatusMessage);
				if (focused) {
					be.setShow(userStatusShow);
					be.setPriority(prefs.getInt(Preferences.DEFAULT_PRIORITY_KEY, 5));
				} else {
					be.setShow(Show.away);
					be.setStatus("Auto away");
					be.setPriority(prefs.getInt(Preferences.AWAY_PRIORITY_KEY, 0));
				}
			}
		};

		this.messageListener = new Listener<MessageModule.MessageEvent>() {

			@Override
			public void handleEvent(MessageEvent be) throws JaxmppException {
				if (be.getChat() != null && be.getMessage().getBody() != null) {

					Uri uri = Uri.parse(ChatHistoryProvider.CHAT_URI + "/" + be.getChat().getJid().getBareJid().toString());

					ContentValues values = new ContentValues();
					values.put(ChatTableMetaData.FIELD_JID, be.getChat().getJid().getBareJid().toString());
					values.put(ChatTableMetaData.FIELD_AUTHOR_JID, be.getChat().getJid().getBareJid().toString());
					values.put(ChatTableMetaData.FIELD_TIMESTAMP, new Date().getTime());
					values.put(ChatTableMetaData.FIELD_BODY, be.getMessage().getBody());
					values.put(ChatTableMetaData.FIELD_STATE, 0);
					values.put(ChatTableMetaData.FIELD_ACCOUNT, be.getSessionObject().getUserJid().getBareJid().toString());

					getContentResolver().insert(uri, values);

					showChatNotification(be);
				}
			}
		};

		this.presenceListener = new Listener<PresenceModule.PresenceEvent>() {

			@Override
			public void handleEvent(PresenceEvent be) throws JaxmppException {
				updateRosterItem(be);
			}
		};
		this.subscribeRequestListener = new Listener<PresenceModule.PresenceEvent>() {

			@Override
			public void handleEvent(PresenceEvent be) throws JaxmppException {
				onSubscribeRequest(be);
			}
		};

		this.rosterListener = new Listener<RosterModule.RosterEvent>() {

			@Override
			public synchronized void handleEvent(final RosterEvent be) throws JaxmppException {
				if (be.getType() == RosterModule.ItemAdded)
					changeRosterItem(be);
				else if (be.getType() == RosterModule.ItemUpdated)
					changeRosterItem(be);
				else if (be.getType() == RosterModule.ItemRemoved)
					changeRosterItem(be);
			}
		};

		this.invalidAuthListener = new Listener<AuthModule.AuthEvent>() {

			@Override
			public void handleEvent(AuthEvent be) throws JaxmppException {
				notificationUpdateFail("Invalid JID or password");
				stopSelf();
			}
		};

		this.stateChangeListener = new Listener<Connector.ConnectorEvent>() {

			@Override
			public void handleEvent(final Connector.ConnectorEvent be) throws JaxmppException {
				if (getState(be.getSessionObject()) == State.connected)
					connectionErrorCounter = 0;
				if (getState(be.getSessionObject()) == State.disconnected)
					reconnectIfAvailable(be.getSessionObject());
				notificationUpdate();
			}
		};

		this.connectorListener = new Listener<Connector.ConnectorEvent>() {

			@Override
			public void handleEvent(ConnectorEvent be) throws JaxmppException {
				if (DEBUG) {
					if (be.getType() == Connector.Error) {
						Log.d(TAG, "Connection error (" + be.getSessionObject().getUserJid() + ") " + be.getCaught() + "  "
								+ be.getStreamError());
						onConnectorError(be);
					} else if (be.getType() == Connector.StreamTerminated) {
						Log.d(TAG, "Stream terminated (" + be.getSessionObject().getUserJid() + ") " + be.getStreamError());
					}
				}
			}
		};

		this.resourceBindListener = new Listener<ResourceBinderModule.ResourceBindEvent>() {

			@Override
			public void handleEvent(ResourceBindEvent be) throws JaxmppException {
				sendUnsentMessages();
			}
		};

	}

	protected synchronized void changeRosterItem(RosterEvent be) {
		Uri insertedItem = ContentUris.withAppendedId(Uri.parse(RosterProvider.CONTENT_URI), be.getItem().getId());
		getApplicationContext().getContentResolver().notifyChange(insertedItem, null);

		if (be.getChangedGroups() != null && !be.getChangedGroups().isEmpty()) {
			for (String gr : be.getChangedGroups()) {

				Uri x = ContentUris.withAppendedId(Uri.parse(RosterProvider.GROUP_URI), gr.hashCode());
				if (DEBUG)
					Log.d(TAG, "Group changed: " + gr + ". Sending notification for " + x);
				getApplicationContext().getContentResolver().notifyChange(x, null, true);
			}
		}

	}

	private void connectAllJaxmpp(Long delay) {
		if (DEBUG)
			Log.d(TAG, "Starting all JAXMPPs");

		for (final JaxmppCore j : getMulti().get()) {
			connectJaxmpp((Jaxmpp) j, delay);
		}

	}

	private void connectJaxmpp(final Jaxmpp jaxmpp, final Date delay) {
		if (isLocked(jaxmpp.getSessionObject())) {
			if (DEBUG)
				Log.d(TAG, "Skip connection for account " + jaxmpp.getSessionObject().getUserJid() + ". Locked.");
			return;
		}

		final Runnable r = new Runnable() {

			@Override
			public void run() {
				if (isDisabled(jaxmpp.getSessionObject())) {
					if (DEBUG)
						Log.d(TAG, "Account" + jaxmpp.getSessionObject().getUserJid() + " disabled. Connection skipped.");
					return;
				}
				if (DEBUG)
					Log.d(TAG, "Start connection for account " + jaxmpp.getSessionObject().getUserJid());
				lock(jaxmpp.getSessionObject(), false);
				usedNetworkType = getActiveNetworkConnectionType();
				if (usedNetworkType != -1) {
					final State state = jaxmpp.getSessionObject().getProperty(Connector.CONNECTOR_STAGE_KEY);
					if (state == null || state == State.disconnected)
						(new Thread() {
							@Override
							public void run() {
								try {
									jaxmpp.login(false);
								} catch (Exception e) {
									++connectionErrorCounter;
									Log.e(TAG, "Can't connect account " + jaxmpp.getSessionObject().getUserJid(), e);
								}
							}
						}).start();
				}
			}
		};

		lock(jaxmpp.getSessionObject(), true);
		if (delay == null)
			r.run();
		else {
			if (DEBUG)
				Log.d(TAG, "Shedule connection for account " + jaxmpp.getSessionObject().getUserJid());
			timer.schedule(new TimerTask() {

				@Override
				public void run() {
					r.run();
				}
			}, delay);
		}
	}

	private void connectJaxmpp(final Jaxmpp jaxmpp, final Long delay) {
		connectJaxmpp(jaxmpp, delay == null ? null : new Date(delay + System.currentTimeMillis()));
	}

	private void disconnectAllJaxmpp() {
		usedNetworkType = -1;
		connectionErrorCounter = 0;
		timer.cancel();
		for (final JaxmppCore j : getMulti().get()) {
			(new Thread() {
				@Override
				public void run() {
					try {
						((Jaxmpp) j).disconnect(false);
					} catch (Exception e) {
						Log.e(TAG, "cant; disconnect account " + j.getSessionObject().getUserJid(), e);
					}
				}
			}).start();
		}
	}

	private Integer getActiveNetworkConnectionType() {
		NetworkInfo info = connManager.getActiveNetworkInfo();
		if (info == null)
			return null;
		if (!info.isConnected())
			return null;
		return info.getType();
	}

	private final MultiJaxmpp getMulti() {
		return ((MessengerApplication) getApplicationContext()).getMultiJaxmpp();
	}

	protected final State getState(SessionObject object) {
		State state = getMulti().get(object).getSessionObject().getProperty(Connector.CONNECTOR_STAGE_KEY);
		return state == null ? State.disconnected : state;
	}

	private void notificationCancel() {
		notificationManager.cancel(NOTIFICATION_ID);
	}

	private void notificationUpdate() {
		int ico = R.drawable.ic_stat_disconnected;
		String notiticationTitle = null;
		String expandedNotificationText = null;

		if (usedNetworkType == -1) {
			ico = R.drawable.ic_stat_disconnected;
			notiticationTitle = "Disconnected";
			expandedNotificationText = "No network";
			if (this.notificationVariant == NotificationVariant.only_connected) {
				notificationCancel();
				return;
			}
		} else {
			int onlineCount = 0;
			int notOnlineCount = 0;
			for (JaxmppCore jaxmpp : getMulti().get()) {
				State state = jaxmpp.getSessionObject().getProperty(Connector.CONNECTOR_STAGE_KEY);
				if (state == State.connected) {
					++onlineCount;
				} else
					++notOnlineCount;
			}

			if (notOnlineCount > 0) {
				ico = R.drawable.ic_stat_connected;
				notiticationTitle = "Connecting";
				expandedNotificationText = "Connecting...";
				if (this.notificationVariant != NotificationVariant.always) {
					notificationCancel();
					return;
				}
			} else if (onlineCount == 0) {
				ico = R.drawable.ic_stat_disconnected;
				notiticationTitle = "Disconnected";
				expandedNotificationText = "No active accounts!";
				if (this.notificationVariant == NotificationVariant.only_connected) {
					notificationCancel();
					return;
				}
			} else {
				ico = R.drawable.ic_stat_connected;
				notiticationTitle = "Connected";
				expandedNotificationText = "Online";
				if (this.notificationVariant == NotificationVariant.only_disconnected) {
					notificationCancel();
					return;
				}

			}

		}

		// // XXX
		// final State state = State.connected;// getState();
		//
		// if (this.notificationVariant == NotificationVariant.none) {
		// notificationCancel();
		// return;
		// }
		//
		// if (state == State.connecting) {
		// ico = R.drawable.ic_stat_connected;
		// notiticationTitle = "Connecting";
		// expandedNotificationText = "Connecting...";
		// if (this.notificationVariant != NotificationVariant.always) {
		// notificationCancel();
		// return;
		// }
		// } else if (state == State.connected) {
		// ico = R.drawable.ic_stat_connected;
		// notiticationTitle = "Connected";
		// expandedNotificationText = "Online";
		// if (this.notificationVariant ==
		// NotificationVariant.only_disconnected) {
		// notificationCancel();
		// return;
		// }
		// } else if (state == State.disconnecting) {
		// ico = R.drawable.ic_stat_disconnected;
		// notiticationTitle = "Disconnecting";
		// expandedNotificationText = "Disconnecting...";
		// if (this.notificationVariant != NotificationVariant.always) {
		// notificationCancel();
		// return;
		// }
		// } else if (state == State.disconnected) {
		// ico = R.drawable.ic_stat_disconnected;
		// notiticationTitle = "Disconnected";
		// expandedNotificationText = "Offline";
		// if (this.notificationVariant == NotificationVariant.only_connected) {
		// notificationCancel();
		// return;
		// }
		// }
		notificationUpdate(ico, notiticationTitle, expandedNotificationText);
	}

	private void notificationUpdate(final int ico, final String notiticationTitle, final String expandedNotificationText) {
		long whenNotify = System.currentTimeMillis();
		Notification notification = new Notification(ico, notiticationTitle, whenNotify);

		// notification.flags = Notification.FLAG_AUTO_CANCEL;
		notification.flags |= Notification.FLAG_ONGOING_EVENT;
		Context context = getApplicationContext();
		String expandedNotificationTitle = context.getResources().getString(R.string.app_name);
		Intent intent = new Intent(context, TigaseMobileMessengerActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);

		notification.setLatestEventInfo(context, expandedNotificationTitle, expandedNotificationText, pendingIntent);

		notificationManager.notify(NOTIFICATION_ID, notification);
	}

	private void notificationUpdateFail(String message) {
		notificationUpdate(R.drawable.ic_stat_disconnected, "Disconnected", "Connection impossible");

		String notiticationTitle = "Connection error";
		String expandedNotificationText = message == null ? notiticationTitle : message;

		Notification notification = new Notification(R.drawable.ic_stat_warning, notiticationTitle, System.currentTimeMillis());
		notification.flags = Notification.FLAG_AUTO_CANCEL;
		// notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.defaults |= Notification.DEFAULT_SOUND;

		notification.flags |= Notification.FLAG_SHOW_LIGHTS;
		notification.ledARGB = Color.GREEN;
		notification.ledOffMS = 500;
		notification.ledOnMS = 500;

		final Context context = getApplicationContext();

		String expandedNotificationTitle = context.getResources().getString(R.string.app_name);
		Intent intent = new Intent(context, TigaseMobileMessengerActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
		notification.setLatestEventInfo(context, expandedNotificationTitle, expandedNotificationText, pendingIntent);

		notificationManager.notify("error", NOTIFICATION_ID, notification);

	}

	private void notificationUpdateReconnect(Date d) {
		if (this.notificationVariant == NotificationVariant.only_connected) {
			notificationCancel();
			return;
		}

		SimpleDateFormat s = new SimpleDateFormat("HH:mm:ss");

		String expandedNotificationText = "Next try on " + s.format(d);
		notificationUpdate(R.drawable.ic_stat_disconnected, "Disconnected", expandedNotificationText);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	protected void onConnectorError(final ConnectorEvent be) {
		if (be.getStreamError() == StreamError.host_unknown) {
			notificationUpdateFail("Connection error: unknown host");
			disable(be.getSessionObject(), true);
		}
	}

	@Override
	public void onCreate() {
		if (DEBUG)
			Log.i(TAG, "onCreate()");
		this.prefs = getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE);
		this.prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);

		this.prefChangeListener = new OnSharedPreferenceChangeListener() {

			@Override
			public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
				if (Preferences.NOTIFICATION_TYPE_KEY.equals(key)) {
					notificationVariant = NotificationVariant.valueOf(sharedPreferences.getString(key, "always"));
					notificationUpdate();
				}
			}
		};

		notificationVariant = NotificationVariant.valueOf(prefs.getString(Preferences.NOTIFICATION_TYPE_KEY, "always"));

		this.connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

		this.myConnReceiver = new ConnReceiver();
		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		registerReceiver(myConnReceiver, filter);

		this.focusChangeReceiver = new ClientFocusReceiver();
		filter = new IntentFilter(TigaseMobileMessengerActivity.CLIENT_FOCUS_MSG);
		registerReceiver(focusChangeReceiver, filter);

		getMulti().addListener(ResourceBinderModule.ResourceBindSuccess, this.resourceBindListener);

		getMulti().addListener(RosterModule.ItemAdded, this.rosterListener);
		getMulti().addListener(RosterModule.ItemRemoved, this.rosterListener);
		getMulti().addListener(RosterModule.ItemUpdated, this.rosterListener);

		getMulti().addListener(PresenceModule.ContactAvailable, this.presenceListener);
		getMulti().addListener(PresenceModule.ContactUnavailable, this.presenceListener);
		getMulti().addListener(PresenceModule.ContactChangedPresence, this.presenceListener);
		getMulti().addListener(PresenceModule.SubscribeRequest, this.subscribeRequestListener);

		getMulti().addListener(AuthModule.AuthFailed, this.invalidAuthListener);
		getMulti().addListener(Connector.StateChanged, this.stateChangeListener);

		getMulti().addListener(MessageModule.MessageReceived, this.messageListener);

		getMulti().addListener(PresenceModule.BeforeInitialPresence, this.presenceSendListener);

		getMulti().addListener(Connector.Error, this.connectorListener);
		getMulti().addListener(Connector.StreamTerminated, this.connectorListener);

		updateJaxmppInstances(getMulti(), getContentResolver(), getResources(), getApplicationContext());

		this.notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		notificationUpdate();
	}

	@Override
	public void onDestroy() {
		serviceActive = false;
		timer.cancel();
		this.prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);

		if (myConnReceiver != null)
			unregisterReceiver(myConnReceiver);

		if (focusChangeReceiver != null)
			unregisterReceiver(focusChangeReceiver);

		Log.i(TAG, "Stopping service");
		reconnect = false;
		disconnectAllJaxmpp();
		usedNetworkType = -1;

		getMulti().removeListener(ResourceBinderModule.ResourceBindSuccess, this.resourceBindListener);

		getMulti().removeListener(PresenceModule.BeforeInitialPresence, this.presenceSendListener);
		getMulti().removeListener(RosterModule.ItemAdded, this.rosterListener);
		getMulti().removeListener(RosterModule.ItemRemoved, this.rosterListener);
		getMulti().removeListener(RosterModule.ItemUpdated, this.rosterListener);

		getMulti().removeListener(PresenceModule.ContactAvailable, this.presenceListener);
		getMulti().removeListener(PresenceModule.ContactUnavailable, this.presenceListener);
		getMulti().removeListener(PresenceModule.ContactChangedPresence, this.presenceListener);
		getMulti().removeListener(PresenceModule.SubscribeRequest, this.subscribeRequestListener);

		getMulti().removeListener(AuthModule.AuthFailed, this.invalidAuthListener);
		getMulti().removeListener(Connector.StateChanged, this.stateChangeListener);

		getMulti().removeListener(MessageModule.MessageReceived, this.messageListener);

		getMulti().removeListener(Connector.Error, this.connectorListener);
		getMulti().removeListener(Connector.StreamTerminated, this.connectorListener);

		notificationCancel();

		super.onDestroy();
	}

	public void onNetworkChanged(final NetworkInfo netInfo) {
		if (DEBUG)
			Log.d(TAG,
					"Network " + netInfo == null ? null : netInfo.getTypeName() + " (" + netInfo == null ? null
							: netInfo.getType() + ") state changed! Currently used=" + usedNetworkType);
		if (usedNetworkType == -1 && netInfo != null && netInfo.isConnected()) {
			if (DEBUG)
				Log.d(TAG, "connect when network became available: " + netInfo.getTypeName());
			reconnect = true;
			connectionErrorCounter = 0;
			connectAllJaxmpp(5000l);
		} else if (netInfo != null && !netInfo.isConnected() && netInfo.getType() == usedNetworkType) {
			if (DEBUG)
				Log.d(TAG, "currently used network disconnected" + netInfo.getTypeName());
			reconnect = false;
			disconnectAllJaxmpp();
		}
	}

	protected void onPageChanged(int pageIndex) {
		if (!focused && pageIndex >= 0) {
			if (DEBUG)
				Log.d(TAG, "Focused. Sending online presence.");
			focused = true;
			int pr = prefs.getInt(Preferences.DEFAULT_PRIORITY_KEY, 5);

			sendAutoPresence(userStatusShow, userStatusMessage, pr, false);
		} else if (focused && pageIndex == -1) {
			if (DEBUG)
				Log.d(TAG, "Sending auto-away presence");
			focused = false;
			int pr = prefs.getInt(Preferences.AWAY_PRIORITY_KEY, 0);
			sendAutoPresence(Show.away, "Auto away", pr, true);
		}
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		if (DEBUG)
			Log.i(TAG, "onStartCommand()");
		if (intent != null) {
			// Log.i(TAG, intent.getExtras().toString());
			if (DEBUG)
				Log.i(TAG, "Found intent! focused=" + intent.getBooleanExtra("focused", false));
			this.focused = intent.getBooleanExtra("focused", false);
		}

		serviceActive = true;

		notificationVariant = NotificationVariant.valueOf(prefs.getString(Preferences.NOTIFICATION_TYPE_KEY, "always"));

		notificationUpdate();

		return START_STICKY;
	}

	protected void onSubscribeRequest(PresenceEvent be) {
		String notiticationTitle = "Authentication request: " + be.getJid();
		String expandedNotificationText = notiticationTitle;

		Notification notification = new Notification(R.drawable.ic_stat_warning, notiticationTitle, System.currentTimeMillis());
		notification.flags = Notification.FLAG_AUTO_CANCEL;
		// notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.defaults |= Notification.DEFAULT_SOUND;

		notification.flags |= Notification.FLAG_SHOW_LIGHTS;
		notification.ledARGB = Color.GREEN;
		notification.ledOffMS = 500;
		notification.ledOnMS = 500;

		final Context context = getApplicationContext();

		String expandedNotificationTitle = context.getResources().getString(R.string.app_name);
		Intent intent = new Intent(context, AuthRequestActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		intent.putExtra("jid", "" + be.getJid());

		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
		notification.setLatestEventInfo(context, expandedNotificationTitle, expandedNotificationText, pendingIntent);

		notificationManager.notify("authRequest:" + be.getJid(), AUTH_REQUEST_NOTIFICATION_ID, notification);
	}

	protected void reconnectIfAvailable(final SessionObject sessionObject) {
		if (!reconnect)
			return;

		if (DEBUG)
			Log.d(TAG, "Preparing for reconnect " + sessionObject.getUserJid());

		final Jaxmpp j = getMulti().get(sessionObject);
		connectJaxmpp(j, calculateNextRestart(5, connectionErrorCounter));

	}

	public void refreshInfos() {
		// XXX
		// final State state = getState();
		// final boolean networkSwitched = getActiveNetworkConnectionType() !=
		// usedNetworkType;
		// final boolean networkAvailable = getActiveNetworkConnectionType() !=
		// null;
		//
		// if (DEBUG) {
		// Log.d(TAG, "State=" + state + "; networkSwitched=" +
		// networkSwitched);
		// NetworkInfo ac = connManager.getActiveNetworkInfo();
		// Log.d(TAG, "Current network: " + (ac == null ? "none" :
		// ac.getTypeName()));
		// }
		//
		// if (networkSwitched && (state == State.connected || state ==
		// State.connecting)) {
		// if (DEBUG)
		// Log.i(TAG, "Network disconnected!");
		// try {
		// jaxmppDisconnect(true);
		// usedNetworkType = null;
		// } catch (JaxmppException e) {
		// Log.w(TAG, "Can't disconnect", e);
		// }
		// } else if (networkAvailable && (state == State.disconnected)) {
		// if (DEBUG)
		// Log.i(TAG, "Network available! Reconnecting!");
		// if (reconnect) {
		// if (connectionErrorCounter < 50)
		// reconnect(true);
		// else {
		// notificationUpdateFail("Can't connect to server");
		// stopSelf();
		// }
		// }
		// }

	}

	private void retrieveVCard(final SessionObject sessionObject, final BareJID jid) {
		try {
			JaxmppCore jaxmpp = getMulti().get(sessionObject);
			if (jaxmpp == null)
				return;
			final RosterItem rosterItem = jaxmpp.getRoster().get(jid);
			jaxmpp.getModulesManager().getModule(VCardModule.class).retrieveVCard(JID.jidInstance(jid),
					new VCardAsyncCallback() {

						@Override
						public void onError(Stanza responseStanza, ErrorCondition error) throws JaxmppException {
						}

						@Override
						public void onTimeout() throws JaxmppException {
						}

						@Override
						protected void onVCardReceived(VCard vcard) throws XMLException {
							try {
								if (vcard.getPhotoVal() != null && vcard.getPhotoVal().length() > 0) {
									ContentValues values = new ContentValues();
									byte[] buffer = Base64.decode(vcard.getPhotoVal());

									values.put(VCardsCacheTableMetaData.FIELD_DATA, buffer);
									getContentResolver().insert(Uri.parse(RosterProvider.VCARD_URI + "/" + jid.toString()),
											values);

									if (rosterItem != null) {
										Uri insertedItem = ContentUris.withAppendedId(Uri.parse(RosterProvider.CONTENT_URI),
												rosterItem.getId());
										getApplicationContext().getContentResolver().notifyChange(insertedItem, null);
									}

								}
							} catch (Exception e) {
								Log.e("tigase", "WTF?", e);
							}
						}
					});
		} catch (Exception e) {
			Log.e("tigase", "WTF?", e);
		}
	}

	private void sendAutoPresence(final Show show, final String status, final int priority, final boolean delayed) {
		if (autoPresenceTask != null) {
			autoPresenceTask.cancel();
			autoPresenceTask = null;
		}

		if (delayed) {
			autoPresenceTask = new TimerTask() {

				@Override
				public void run() {
					autoPresenceTask = null;
					try {
						for (JaxmppCore jaxmpp : getMulti().get()) {
							final PresenceModule presenceModule = jaxmpp.getModulesManager().getModule(PresenceModule.class);
							presenceModule.setPresence(show, status, priority);
						}
					} catch (Exception e) {
						Log.e(TAG, "Can't send auto presence!", e);
					}
				}
			};
			timer.schedule(autoPresenceTask, 1000 * 60);
		} else {
			try {
				for (JaxmppCore jaxmpp : getMulti().get()) {
					final PresenceModule presenceModule = jaxmpp.getModulesManager().getModule(PresenceModule.class);
					presenceModule.setPresence(show, status, priority);
				}
			} catch (Exception e) {
				Log.e(TAG, "Can't send auto presence!", e);
			}
		}

	}

	protected void sendUnsentMessages() {
		final Cursor c = getApplication().getContentResolver().query(Uri.parse(ChatHistoryProvider.UNSENT_MESSAGES_URI), null,
				null, null, null);
		try {
			c.moveToFirst();
			if (c.isAfterLast())
				return;
			do {
				long id = c.getLong(c.getColumnIndex(ChatTableMetaData.FIELD_ID));
				String jid = c.getString(c.getColumnIndex(ChatTableMetaData.FIELD_JID));
				String body = c.getString(c.getColumnIndex(ChatTableMetaData.FIELD_BODY));
				String threadId = c.getString(c.getColumnIndex(ChatTableMetaData.FIELD_THREAD_ID));
				BareJID account = BareJID.bareJIDInstance(c.getString(c.getColumnIndex(ChatTableMetaData.FIELD_ACCOUNT)));

				final JID ownJid = getMulti().get(account).getSessionObject().getProperty(
						ResourceBinderModule.BINDED_RESOURCE_JID);
				final String nickname = ownJid.getLocalpart();

				Message msg = Message.create();
				msg.setType(StanzaType.chat);
				msg.setTo(JID.jidInstance(jid));
				msg.setBody(body);
				if (threadId != null && threadId.length() > 0)
					msg.setThread(threadId);
				if (DEBUG)
					Log.i(TAG, "Found unsetn message: " + jid + " :: " + body);

				try {
					getMulti().get(account).send(msg);

					ContentValues values = new ContentValues();
					values.put(ChatTableMetaData.FIELD_ID, id);
					values.put(ChatTableMetaData.FIELD_AUTHOR_JID, ownJid.getBareJid().toString());
					values.put(ChatTableMetaData.FIELD_AUTHOR_NICKNAME, nickname);
					values.put(ChatTableMetaData.FIELD_STATE, ChatTableMetaData.STATE_OUT_SENT);

					getContentResolver().update(Uri.parse(ChatHistoryProvider.CHAT_URI + "/" + jid + "/" + id), values, null,
							null);
				} catch (JaxmppException e) {
					if (DEBUG)
						Log.d(TAG, "Can't send message");
				}

				c.moveToNext();
			} while (!c.isAfterLast());
		} catch (XMLException e) {
			Log.e(TAG, "WTF??", e);
		} finally {
			c.close();
		}
	}

	protected void showChatNotification(final MessageEvent event) throws XMLException {
		int ico = R.drawable.ic_stat_message;

		String n = (new RosterDisplayTools(getApplicationContext())).getDisplayName(event.getSessionObject(),
				event.getMessage().getFrom().getBareJid());
		if (n == null)
			n = event.getMessage().getFrom().toString();

		String notiticationTitle = "Message from " + n;
		String expandedNotificationText = notiticationTitle;

		long whenNotify = System.currentTimeMillis();
		Notification notification = new Notification(ico, notiticationTitle, whenNotify);
		notification.flags = Notification.FLAG_AUTO_CANCEL;
		// notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.defaults |= Notification.DEFAULT_SOUND;

		notification.flags |= Notification.FLAG_SHOW_LIGHTS;
		notification.ledARGB = Color.GREEN;
		notification.ledOffMS = 500;
		notification.ledOnMS = 500;

		final Context context = getApplicationContext();

		String expandedNotificationTitle = context.getResources().getString(R.string.app_name);
		Intent intent = new Intent(context, TigaseMobileMessengerActivity.class);
		intent.setAction("messageFrom-" + event.getMessage().getFrom());
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		intent.putExtra("jid", "" + event.getMessage().getFrom());
		if (event.getChat() != null)
			intent.putExtra("chatId", event.getChat().getId());

		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
		notification.setLatestEventInfo(context, expandedNotificationTitle, expandedNotificationText, pendingIntent);

		if (currentChatIdFocus != event.getChat().getId())
			notificationManager.notify("chatId:" + event.getChat().getId(), CHAT_NOTIFICATION_ID, notification);

	}

	protected synchronized void updateRosterItem(final PresenceEvent be) throws XMLException {
		RosterItem it = getMulti().get(be.getSessionObject()).getRoster().get(be.getJid().getBareJid());
		if (it != null) {
			// Log.i(TAG, "Item " + it.getJid() + " has changed presence");

			Element x = be != null && be.getPresence() != null ? be.getPresence().getChildrenNS("x", "vcard-temp:x:update")
					: null;
			if (x != null) {
				for (Element c : x.getChildren()) {
					if (c.getName().equals("photo") && c.getValue() != null) {
						String sha = c.getValue();
						String isha = it.getData("photo");
						if (sha != null && (isha == null || !isha.equalsIgnoreCase(sha))) {
							retrieveVCard(be.getSessionObject(), it.getJid());
						}
					} else if (c.getName().equals("photo") && c.getValue() == null) {
					}
				}
			}
		}
	}

}