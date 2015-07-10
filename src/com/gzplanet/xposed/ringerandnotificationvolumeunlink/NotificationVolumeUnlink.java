package com.gzplanet.xposed.ringerandnotificationvolumeunlink;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class NotificationVolumeUnlink implements IXposedHookZygoteInit, IXposedHookLoadPackage {
	final static String PKGNAME_SETTINGS = "com.android.settings";
	final static String CLASSNAME_AUDIO_SERVICE = "android.media.AudioService";
	final static String CLASSNAME_VOLUME_SEEKBAR_PREFERENCE = "com.android.settings.notification.VolumeSeekBarPreference";
	final static String CLASSNAME_SEEKBARVOLUMIZER_RECEIVER = "android.preference.SeekBarVolumizer.Receiver";

	/* The audio stream for notifications */
	final static int STREAM_NOTIFICATION = 5;

	private static final String KEY_SOUND = "sound";
	private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
	private static final String KEY_RING_VOLUME = "ring_volume";
	private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
	private static final String INTERNAL_RINGER_MODE_CHANGED_ACTION = "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";
	private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
	private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";

	private Object mNotificationVolume;
	private PreferenceCategory mSound;

	@Override
	public void initZygote(StartupParam startupParam) {
		try {
			final Class<?> classAudioService = XposedHelpers.findClass(CLASSNAME_AUDIO_SERVICE, null);

			findAndHookMethod(classAudioService, "updateStreamVolumeAlias", boolean.class, new XC_MethodHook() {
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					// get existing volume alias values
					int[] streamVolumeAlias = (int[]) getObjectField(param.thisObject, "mStreamVolumeAlias");

					// unlink notification stream from ring stream
					streamVolumeAlias[STREAM_NOTIFICATION] = STREAM_NOTIFICATION;
					XposedHelpers.setObjectField(param.thisObject, "mStreamVolumeAlias", streamVolumeAlias);
				};
			});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}

		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
			try {
				final Class<?> classSeekBarVolumizerReceiver = XposedHelpers.findClass(CLASSNAME_SEEKBARVOLUMIZER_RECEIVER, null);

				findAndHookMethod(classSeekBarVolumizerReceiver, "onReceive", Context.class, Intent.class, new XC_MethodReplacement() {

					@Override
					protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
						// outer class objects
						Object outerObject = XposedHelpers.getSurroundingThis(param.thisObject);
						boolean mNotificationOrRing = XposedHelpers.getBooleanField(outerObject, "mNotificationOrRing");
						int mStreamType = XposedHelpers.getIntField(outerObject, "mStreamType");
						boolean mAffectedByRingerMode = XposedHelpers.getBooleanField(outerObject, "mAffectedByRingerMode");
						Object mSeekBar = XposedHelpers.getObjectField(outerObject, "mSeekBar");
						Object mAudioManager = XposedHelpers.getObjectField(outerObject, "mAudioManager");
						Object mUiHandler = XposedHelpers.getObjectField(outerObject, "mUiHandler");

						Intent intent = (Intent) param.args[1];
						final String action = intent.getAction();
						if (VOLUME_CHANGED_ACTION.equals(action)) {
							int streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
							int streamValue = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
							final boolean streamMatch = (streamType == mStreamType);
							if (mSeekBar != null && streamMatch && streamValue != -1) {
								final boolean muted = (Boolean) XposedHelpers.callMethod(mAudioManager, "isStreamMute", mStreamType);
								XposedHelpers.callMethod(mUiHandler, "postUpdateSlider", streamValue, muted);
							}
						} else if (INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
							if (mNotificationOrRing) {
								int ringerMode = (Integer) XposedHelpers.callMethod(mAudioManager, "getRingerModeInternal");
								XposedHelpers.setIntField(outerObject, "mRingerMode", ringerMode);
							}
							if (mAffectedByRingerMode)
								XposedHelpers.callMethod(outerObject, "updateSlider");
						}

						return null;
					}
				});
			} catch (XposedHelpers.ClassNotFoundError e) {
				XposedBridge.log(e.getMessage());
			} catch (Throwable t) {
				XposedBridge.log(t);
			}
		}
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals(PKGNAME_SETTINGS))
			return;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			// Android 5.0 or higher
			XposedHelpers.findAndHookMethod("com.android.settings.notification.NotificationSettings", lpparam.classLoader, "onCreate", Bundle.class,
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							try {
								Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
								int iconId;
								int titleId = context.getResources().getIdentifier("notification_volume_option_title", "string", PKGNAME_SETTINGS);
								int muteIconId = 0;
								if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
									muteIconId = context.getResources().getIdentifier("ic_audio_ring_notif_mute", "drawable", "android");
									iconId = context.getResources().getIdentifier("ic_audio_ring_notif", "drawable", "android");
								} else {
									iconId = context.getResources().getIdentifier("ring_notif", "drawable", PKGNAME_SETTINGS);
								}

								final Class<?> classVolumeSeekBarPreference = XposedHelpers.findClass(CLASSNAME_VOLUME_SEEKBAR_PREFERENCE,
										context.getClassLoader());
								mNotificationVolume = XposedHelpers.newInstance(classVolumeSeekBarPreference, context);

								mSound = (PreferenceCategory) XposedHelpers.callMethod(param.thisObject, "findPreference", KEY_SOUND);

								if (mSound != null && mNotificationVolume != null) {
									// put new notification volume slider under
									// ringer volume slider
									final Preference prefRinger = mSound.findPreference(KEY_RING_VOLUME);
									if (prefRinger != null)
										XposedHelpers.callMethod(mNotificationVolume, "setOrder", prefRinger.getOrder() + 1);

									mSound.addPreference((Preference) mNotificationVolume);

									XposedHelpers.callMethod(mNotificationVolume, "setKey", KEY_NOTIFICATION_VOLUME);
									if (iconId > 0)
										XposedHelpers.callMethod(mNotificationVolume, "setIcon", iconId);
									if (titleId > 0)
										XposedHelpers.callMethod(mNotificationVolume, "setTitle", titleId);
									if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP)
										XposedHelpers.callMethod(param.thisObject, "initVolumePreference", KEY_NOTIFICATION_VOLUME, STREAM_NOTIFICATION,
												muteIconId);
									else
										XposedHelpers.callMethod(param.thisObject, "initVolumePreference", KEY_NOTIFICATION_VOLUME, STREAM_NOTIFICATION);
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
		} else {
			// Android 4.4.4 or lower
			XposedHelpers.findAndHookMethod("com.android.settings.RingerVolumePreference", lpparam.classLoader, "onBindDialogView", View.class,
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							View view = (View) param.args[0];

							// show notification section and change ringtone
							// section description
							try {
								Resources res = view.getContext().getResources();
								Context xContext = view.getContext().createPackageContext(NotificationVolumeUnlink.class.getPackage().getName(),
										Context.CONTEXT_IGNORE_SECURITY);

								int sectionId = res.getIdentifier("notification_section", "id", PKGNAME_SETTINGS);
								view.findViewById(sectionId).setVisibility(View.VISIBLE);

								int ringerTextId = res.getIdentifier("ringer_description_text", "id", PKGNAME_SETTINGS);
								String ringDesc = xContext.getResources().getString(R.string.volume_ring_description);
								((TextView) view.findViewById(ringerTextId)).setText(ringDesc);
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
		}
	}
}
