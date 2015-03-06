package com.gzplanet.xposed.ringerandnotificationvolumeunlink;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import android.content.Context;
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
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class NotificationVolumeUnlink implements IXposedHookZygoteInit, IXposedHookLoadPackage {
	final static String PKGNAME_SETTINGS = "com.android.settings";
	final static String CLASSNAME_AUDIO_SERVICE = "android.media.AudioService";
	final static String CLASSNAME_VOLUME_SEEKBAR_PREFERENCE = "com.android.settings.notification.VolumeSeekBarPreference";

	/* The audio stream for notifications */
	final static int STREAM_NOTIFICATION = 5;

	private static final String KEY_SOUND = "sound";
	private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
	private static final String KEY_RING_VOLUME = "ring_volume";

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
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals(PKGNAME_SETTINGS))
			return;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			// Android 5.0 or higher
			XposedHelpers.findAndHookMethod("com.android.settings.notification.NotificationSettings",
					lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							try {
								Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
								int iconId = context.getResources().getIdentifier("ring_notif", "drawable",
										PKGNAME_SETTINGS);
								int titleId = context.getResources().getIdentifier("notification_volume_option_title",
										"string", PKGNAME_SETTINGS);

								final Class<?> classVolumeSeekBarPreference = XposedHelpers.findClass(
										CLASSNAME_VOLUME_SEEKBAR_PREFERENCE, context.getClassLoader());
								mNotificationVolume = XposedHelpers.newInstance(classVolumeSeekBarPreference, context);

								mSound = (PreferenceCategory) XposedHelpers.callMethod(param.thisObject,
										"findPreference", KEY_SOUND);

								if (mSound != null && mNotificationVolume != null) {
									// put new notification volume slider under
									// ringer volume slider
									final Preference prefRinger = mSound.findPreference(KEY_RING_VOLUME);
									if (prefRinger != null)
										XposedHelpers.callMethod(mNotificationVolume, "setOrder",
												prefRinger.getOrder() + 1);

									mSound.addPreference((Preference) mNotificationVolume);

									XposedHelpers.callMethod(mNotificationVolume, "setKey", KEY_NOTIFICATION_VOLUME);
									XposedHelpers.callMethod(mNotificationVolume, "setIcon", iconId);
									XposedHelpers.callMethod(mNotificationVolume, "setTitle", titleId);
									XposedHelpers.callMethod(mNotificationVolume, "setCallback",
											XposedHelpers.getObjectField(param.thisObject, "mVolumeCallback"));
									XposedHelpers.callMethod(mNotificationVolume, "setStream", STREAM_NOTIFICATION);
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
		} else {
			// Android 4.4.4 or lower
			XposedHelpers.findAndHookMethod("com.android.settings.RingerVolumePreference", lpparam.classLoader,
					"onBindDialogView", View.class, new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							View view = (View) param.args[0];

							// show notification section and change ringtone
							// section description
							try {
								Resources res = view.getContext().getResources();
								Context xContext = view.getContext().createPackageContext(
										NotificationVolumeUnlink.class.getPackage().getName(),
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
