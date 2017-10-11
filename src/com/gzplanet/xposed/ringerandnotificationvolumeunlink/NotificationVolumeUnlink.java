package com.gzplanet.xposed.ringerandnotificationvolumeunlink;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SeekBarVolumizer;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

public class NotificationVolumeUnlink implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    final static String PKGNAME_SETTINGS = "com.android.settings";
    final static String PKGNAME_SYSTEMUI = "com.android.systemui";
    final static String PKGNAME_SYSTEM_SERVICE = "android";
    final static String CLASSNAME_AUDIO_SERVICE = "android.media.AudioService";
    final static String CLASSNAME_AUDIO_SERVICE_MM = "com.android.server.audio.AudioService";
    final static String CLASSNAME_VOLUME_SEEKBAR_PREFERENCE = "com.android.settings.notification.VolumeSeekBarPreference";
    final static String CLASSNAME_SEEKBARVOLUMIZER_RECEIVER = "android.preference.SeekBarVolumizer.Receiver";
    final static String CLASSNAME_SEEKBARVOLUMIZER = "android.preference.SeekBarVolumizer";
    final static String CLASSNAME_XPERIA_VOLUMESLIDERS = "com.sonymobile.systemui.volume.SomcExpandedVolumeSliders";
    final static String CLASSNAME_VOLUMEDIALOG = "com.android.systemui.volume.VolumeDialog";
    final static String CLASSNAME_VOLUMEDIALOGCONTROLLER = "com.android.systemui.volume.VolumeDialogController";
    final static String CLASSNAME_ZENMODECONTROLLER = "com.android.systemui.statusbar.policy.ZenModeController";
    final static String CLASSNAME_CALLBACK = "com.android.systemui.volume.VolumeDialog.Callback";
    final static String CLASSNAME_VOLUMEROW = "com.android.systemui.volume.VolumeDialog.VolumeRow";

    /* The audio stream for ringer & notifications */
    final static int STREAM_RINGER = 2;
    final static int STREAM_NOTIFICATION = 5;

    private static final String TAG_NOTIFICATION_SLIDER = "NOTIFICATION_SLIDER";
    private static final String KEY_SOUND = "sound";
    private static final String KEY_SOUND_N = "sound_settings";
    private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
    private static final String KEY_RING_VOLUME = "ring_volume";
    private static final String KEY_VIBRATE_WHEN_RINGING = "vibrate_when_ringing";
    private static final String KEY_ROOT = "notification_settings";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String INTERNAL_RINGER_MODE_CHANGED_ACTION = "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";
    private static final String ACTION_INTERRUPTION_FILTER_CHANGED = "android.app.action.INTERRUPTION_FILTER_CHANGED";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private static final int VERSION_CODES_N = 24;

    private Object mNotificationVolume;
    private Object mSomcExpandedVolumeSliders;
    private Handler mSlidersHandler;
    private Object mSoundN;
    private SeekBarVolumizer mNotifSeekbarVolumizer;
    private SeekBarVolumizer.Callback msbvc;
    private SeekBar mSeekBar;
    private Context mContext;
    private ImageView mNotifImg;
    private int mMuteIconId;

    @Override
    public void initZygote(StartupParam startupParam) {
        // pre Marshmallow implementation
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                final Class<?> classAudioService = XposedHelpers.findClass(CLASSNAME_AUDIO_SERVICE, null);

                findAndHookMethod(classAudioService, "updateStreamVolumeAlias", boolean.class, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // get existing volume alias values
                        int[] streamVolumeAlias = (int[]) getObjectField(param.thisObject, "mStreamVolumeAlias");

                        // unlink notification stream from ring stream
                        streamVolumeAlias[STREAM_NOTIFICATION] = STREAM_NOTIFICATION;
                        XposedHelpers.setObjectField(param.thisObject, "mStreamVolumeAlias", streamVolumeAlias);
                    }

                    ;
                });
            } catch (ClassNotFoundError e) {
                XposedBridge.log(e.getMessage());
            }
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            try {
                final Class<?> classSeekBarVolumizerReceiver = XposedHelpers.findClass(
                        CLASSNAME_SEEKBARVOLUMIZER_RECEIVER, null);

                if (Build.VERSION.SDK_INT >= VERSION_CODES_N) {
                    findAndHookMethod(classSeekBarVolumizerReceiver, "updateVolumeSlider", int.class, int.class,
                            new XC_MethodReplacement() {

                                @Override
                                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                    // outer class objects
                                    Object outerObject = XposedHelpers.getSurroundingThis(param.thisObject);
                                    Object mSeekBar = XposedHelpers.getObjectField(outerObject, "mSeekBar");
                                    Object mAudioManager = XposedHelpers.getObjectField(outerObject, "mAudioManager");
                                    int mStreamType = XposedHelpers.getIntField(outerObject, "mStreamType");
                                    Object mUiHandler = XposedHelpers.getObjectField(outerObject, "mUiHandler");

                                    int streamType = (int) param.args[0];
                                    int streamValue = (int) param.args[1];

                                    final boolean streamMatch = (streamType == mStreamType);
                                    if (mSeekBar != null && streamMatch && streamValue != -1) {
                                        final boolean muted = (Boolean) XposedHelpers.callMethod(mAudioManager,
                                                "isStreamMute", mStreamType) || streamValue == 0;
                                        int mLastAudibleStreamVolume = XposedHelpers.getIntField(outerObject,
                                                "mLastAudibleStreamVolume");
                                        XposedHelpers.callMethod(mUiHandler, "postUpdateSlider", streamValue,
                                                mLastAudibleStreamVolume, muted);
                                    }
                                    return null;
                                }
                            });
                } else {
                    findAndHookMethod(classSeekBarVolumizerReceiver, "onReceive", Context.class, Intent.class,
                            new XC_MethodReplacement() {

                                @Override
                                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                    // outer class objects
                                    Object outerObject = XposedHelpers.getSurroundingThis(param.thisObject);
                                /*
                                 * Check if SeekBarVolumizer class is being used
								 * For Xperia 5.1 ROM, another class
								 * SomcExpandedVolumeSliders might be returned
								 */
                                    final boolean isSeekBarVolumizerClass = CLASSNAME_SEEKBARVOLUMIZER.equals(outerObject
                                            .getClass().getName());

                                    boolean mNotificationOrRing = XposedHelpers.getBooleanField(outerObject,
                                            "mNotificationOrRing");
                                    int mStreamType = XposedHelpers.getIntField(outerObject, "mStreamType");
                                    boolean mAffectedByRingerMode = XposedHelpers.getBooleanField(outerObject,
                                            "mAffectedByRingerMode");
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
                                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                                                final boolean muted = (Boolean) XposedHelpers.callMethod(mAudioManager,
                                                        "isStreamMute", mStreamType) || streamValue == 0;
                                                int mLastAudibleStreamVolume = XposedHelpers.getIntField(outerObject,
                                                        "mLastAudibleStreamVolume");
                                                XposedHelpers.callMethod(mUiHandler, "postUpdateSlider", streamValue,
                                                        mLastAudibleStreamVolume, muted);
                                            } else {
                                                final boolean muted = (Boolean) XposedHelpers.callMethod(mAudioManager,
                                                        "isStreamMute", mStreamType);
                                                XposedHelpers
                                                        .callMethod(mUiHandler, "postUpdateSlider", streamValue, muted);
                                            }
                                        }
                                    } else if (INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                                        if (mNotificationOrRing) {
                                            int ringerMode = (Integer) XposedHelpers.callMethod(mAudioManager,
                                                    "getRingerModeInternal");
                                            XposedHelpers.setIntField(outerObject, "mRingerMode", ringerMode);
                                        }
                                        if (mAffectedByRingerMode && isSeekBarVolumizerClass)
                                            XposedHelpers.callMethod(outerObject, "updateSlider");
                                    } else if (ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)) {
                                        Object mNotificationManager = XposedHelpers.getObjectField(outerObject,
                                                "mNotificationManager");
                                        int zenMode = (Integer) XposedHelpers
                                                .callMethod(mNotificationManager, "getZenMode");
                                        XposedHelpers.setIntField(outerObject, "mZenMode", zenMode);
                                        XposedHelpers.callMethod(outerObject, "updateSlider");
                                    }

                                    return null;
                                }
                            });
                }
            } catch (XposedHelpers.ClassNotFoundError e) {
                XposedBridge.log(e.getMessage());
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKGNAME_SETTINGS) && !lpparam.packageName.equals(PKGNAME_SYSTEMUI)
                && !lpparam.packageName.equals(PKGNAME_SYSTEM_SERVICE))
            return;

        if (lpparam.packageName.equals(PKGNAME_SETTINGS)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

                String notifClass;
                if (Build.VERSION.SDK_INT >= VERSION_CODES_N) {
                    // Android 7.0 or higher
                    notifClass = "com.android.settings.notification.SoundSettings";
                } else {
                    // Android 5.0 or higher
                    notifClass = "com.android.settings.notification.NotificationSettings";
                }
                XposedHelpers.findAndHookMethod(notifClass,
                        lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject,
                                            "mContext");
                                    int iconId;
                                    int titleId = context.getResources().getIdentifier(
                                            "notification_volume_option_title", "string", PKGNAME_SETTINGS);
                                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                                        mMuteIconId = context.getResources().getIdentifier("ic_audio_notification_mute",
                                                "drawable", "android");
                                        iconId = context.getResources().getIdentifier("ic_audio_notification",
                                                "drawable", "android");
                                    } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
                                        mMuteIconId = context.getResources().getIdentifier("ic_audio_ring_notif_mute",
                                                "drawable", "android");
                                        iconId = context.getResources().getIdentifier("ic_audio_ring_notif",
                                                "drawable", "android");
                                    } else {
                                        iconId = context.getResources().getIdentifier("ring_notif", "drawable",
                                                PKGNAME_SETTINGS);
                                    }

                                    final Class<?> classVolumeSeekBarPreference = XposedHelpers.findClass(
                                            CLASSNAME_VOLUME_SEEKBAR_PREFERENCE, context.getClassLoader());
                                    mNotificationVolume = XposedHelpers.newInstance(classVolumeSeekBarPreference,
                                            context);

                                    mSoundN = XposedHelpers.callMethod(param.thisObject, "findPreference",
                                            Build.VERSION.SDK_INT >= VERSION_CODES_N ? KEY_SOUND_N : KEY_SOUND);
                                    if (mSoundN != null && mNotificationVolume != null) {
                                        // put new notification volume slider
                                        // under ringer volume slider
                                        final Object prefRinger = XposedHelpers.callMethod(mSoundN, "findPreference", KEY_RING_VOLUME);
                                        if (prefRinger != null) {
                                            final int order = (int) XposedHelpers.callMethod(prefRinger, "getOrder");
                                            XposedHelpers.callMethod(mNotificationVolume, "setOrder", order + 1);
                                            XposedHelpers.callMethod(mSoundN, "addPreference", mNotificationVolume);

                                            XposedHelpers
                                                    .callMethod(mNotificationVolume, "setKey", KEY_NOTIFICATION_VOLUME);
                                            if (iconId > 0)
                                                XposedHelpers.callMethod(mNotificationVolume, "setIcon", iconId);
                                            if (titleId > 0)
                                                XposedHelpers.callMethod(mNotificationVolume, "setTitle", titleId);
                                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP)
                                                XposedHelpers.callMethod(param.thisObject, "initVolumePreference",
                                                        KEY_NOTIFICATION_VOLUME, STREAM_NOTIFICATION, mMuteIconId);
                                            else
                                                XposedHelpers.callMethod(param.thisObject, "initVolumePreference",
                                                        KEY_NOTIFICATION_VOLUME, STREAM_NOTIFICATION);
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                            }
                        });

				/* Xperia 6.0 ROM only */
                try {
                    XposedHelpers.findAndHookMethod("com.android.settings.notification.NotificationSettings",
                            lpparam.classLoader, "refreshSomcVolumePrefs", new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    PreferenceScreen root = (PreferenceScreen) XposedHelpers.callMethod(param.thisObject, "findPreference", KEY_ROOT);

                                    Preference prefNotification = root.findPreference(KEY_NOTIFICATION_VOLUME);
                                    Preference prefRinger = root.findPreference(KEY_RING_VOLUME);
                                    Preference prefVibrateWhenRing = root.findPreference(KEY_VIBRATE_WHEN_RINGING);

                                    if (prefNotification == null && mNotificationVolume != null) {
                                        int order = prefRinger.getOrder();
                                        XposedHelpers.callMethod(mNotificationVolume, "setOrder", order + 1);

                                        if (prefVibrateWhenRing != null) {
                                            root.removePreference(prefVibrateWhenRing);
                                            XposedHelpers.callMethod(prefVibrateWhenRing, "setOrder", order + 2);
                                        }

                                        root.addPreference((Preference) mNotificationVolume);

                                        if (prefVibrateWhenRing != null)
                                            root.addPreference(prefVibrateWhenRing);

                                        XposedHelpers.callMethod(param.thisObject, "initVolumePreference",
                                                KEY_NOTIFICATION_VOLUME, STREAM_NOTIFICATION, mMuteIconId);

                                        //XposedBridge.log("Notification preference added completely");
                                    }
                                }
                            });
                } catch (ClassNotFoundError e) {
                    XposedBridge.log("Class NotificationSettings not found");
                } catch (NoSuchMethodError e) {
                    XposedBridge.log("Method refreshSomcVolumePrefs not found");
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
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

                                    int ringerTextId = res.getIdentifier("ringer_description_text", "id",
                                            PKGNAME_SETTINGS);
                                    String ringDesc = xContext.getResources().getString(
                                            R.string.volume_ring_description);
                                    ((TextView) view.findViewById(ringerTextId)).setText(ringDesc);
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                            }
                        });
            }
        } else if (lpparam.packageName.equals(PKGNAME_SYSTEMUI)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                /* Xperia 5.1 ROM only */
                try {
                    XposedHelpers.findClass(CLASSNAME_XPERIA_VOLUMESLIDERS, lpparam.classLoader);

                    XposedHelpers.findAndHookMethod(CLASSNAME_XPERIA_VOLUMESLIDERS, lpparam.classLoader, "showPanel",
                            int.class, new XC_MethodHook() {
                                @TargetApi(Build.VERSION_CODES.LOLLIPOP_MR1)
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    // XposedBridge.log("SomcExpandedVolumeSliders:showPanel");
                                    mSomcExpandedVolumeSliders = param.thisObject;
                                    mSlidersHandler = (Handler) XposedHelpers.getObjectField(
                                            mSomcExpandedVolumeSliders, "mHandler");
                                    if (mContext == null)
                                        mContext = (Context) XposedHelpers.getObjectField(mSomcExpandedVolumeSliders,
                                                "mContext");
                                    Resources res = mContext.getResources();
                                    LinearLayout mPanelMain = (LinearLayout) XposedHelpers.getObjectField(
                                            mSomcExpandedVolumeSliders, "mPanelMain");

                                    if (mPanelMain.findViewWithTag(TAG_NOTIFICATION_SLIDER) == null) {
                                        try {
                                            if (msbvc == null) {
                                                msbvc = new SeekBarVolumizer.Callback() {
                                                    @Override
                                                    public void onSampleStarting(SeekBarVolumizer sbv) {
                                                        SeekBarVolumizer currentPlayingSeekBarVolumizer = (SeekBarVolumizer) XposedHelpers
                                                                .getObjectField(mSomcExpandedVolumeSliders,
                                                                        "mCurrentPlayingSeekBarVolumizer");
                                                        if (currentPlayingSeekBarVolumizer != null
                                                                && currentPlayingSeekBarVolumizer != sbv)
                                                            currentPlayingSeekBarVolumizer.stopSample();
                                                        XposedHelpers.setObjectField(mSomcExpandedVolumeSliders,
                                                                "mCurrentPlayingSeekBarVolumizer", sbv);
                                                        if (sbv != null) {
                                                            mSlidersHandler.removeMessages(1);
                                                            mSlidersHandler.sendEmptyMessageDelayed(1, 2000L);
                                                        }
                                                    }

                                                    @Override
                                                    public void onMuted(boolean arg0) {
                                                    }

                                                    @Override
                                                    public void onProgressChanged(SeekBar arg0, int arg1, boolean arg2) {
                                                    }
                                                };
                                            }

                                            if (mNotifSeekbarVolumizer == null) {
                                                mNotifSeekbarVolumizer = new SeekBarVolumizer(mContext,
                                                        STREAM_NOTIFICATION, null, msbvc) {
                                                    public void onProgressChanged(SeekBar seekBar, int progress,
                                                                                  boolean fromTouch) {
                                                        super.onProgressChanged(seekBar, progress, fromTouch);
                                                        final int imgResId = mContext.getResources().getIdentifier(
                                                                progress > 0 ? "ic_notification_audible"
                                                                        : "ic_ringer_vibrate", "drawable",
                                                                PKGNAME_SYSTEMUI);
                                                        mNotifImg.setImageResource(imgResId);
                                                    }
                                                };
                                                mNotifSeekbarVolumizer.start();
                                            }

                                            // construct the new slider
                                            // programmatically
                                            LinearLayout ll = new LinearLayout(mContext);
                                            ll.setOrientation(LinearLayout.HORIZONTAL);
                                            final int paddingBetweenSliders = (int) res.getDimensionPixelSize(res
                                                    .getIdentifier("volume_panel_padding_between_sliders", "dimen",
                                                            PKGNAME_SYSTEMUI));
                                            ll.setPadding(0, paddingBetweenSliders, 0, 0);
                                            ll.setLayoutParams(new LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.FILL_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT));
                                            ll.setTag(TAG_NOTIFICATION_SLIDER);

                                            final int imgResId = res.getIdentifier("ic_notification_audible",
                                                    "drawable", PKGNAME_SYSTEMUI);
                                            if (mNotifImg == null) {
                                                mNotifImg = new ImageView(mContext);
                                                mNotifImg.setPaddingRelative((int) TypedValue.applyDimension(
                                                        TypedValue.COMPLEX_UNIT_DIP, 16, res.getDisplayMetrics()),
                                                        (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                                                                res.getDisplayMetrics()), 0, 0);
                                                mNotifImg.setLayoutParams(new LinearLayout.LayoutParams(
                                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                                        LinearLayout.LayoutParams.WRAP_CONTENT));
                                                mNotifImg.setImageResource(imgResId);
                                            }

                                            LinearLayout ll2 = new LinearLayout(mContext);
                                            ll2.setOrientation(LinearLayout.VERTICAL);
                                            ll2.setPaddingRelative(0, 0, (int) TypedValue.applyDimension(
                                                    TypedValue.COMPLEX_UNIT_DIP, 16, res.getDisplayMetrics()), 0);
                                            ll2.setLayoutParams(new LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.FILL_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT));

                                            final int bgResId = res.getIdentifier("btn_borderless_rect", "drawable",
                                                    PKGNAME_SYSTEMUI);
                                            final int textStyleResId = res.getIdentifier(
                                                    "TextAppearance.Material.Body1", "style", PKGNAME_SYSTEMUI);
                                            final int textId = res.getIdentifier("volume_dialog_notifications",
                                                    "string", PKGNAME_SYSTEMUI);
                                            final String text = res.getString(textId);
                                            TextView tv = new TextView(mContext);
                                            tv.setLayoutParams(new LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.FILL_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT));
                                            tv.setPaddingRelative((int) TypedValue.applyDimension(
                                                    TypedValue.COMPLEX_UNIT_DIP, 16, res.getDisplayMetrics()), 0, 0, 0);
                                            tv.setText(text);
                                            tv.setTextAppearance(mContext, textStyleResId);
                                            tv.setBackgroundResource(bgResId);

                                            if (mSeekBar == null) {
                                                mSeekBar = new SeekBar(mContext);
                                                mSeekBar.setLayoutParams(new LinearLayout.LayoutParams(
                                                        LinearLayout.LayoutParams.FILL_PARENT,
                                                        LinearLayout.LayoutParams.WRAP_CONTENT));
                                                mSeekBar.setMax(7);
                                                mSeekBar.setProgress(4);
                                                mNotifSeekbarVolumizer.setSeekBar(mSeekBar);
                                            }

                                            ll2.addView(tv);
                                            ll2.addView(mSeekBar);
                                            ll.addView(mNotifImg);
                                            ll.addView(ll2);

                                            mPanelMain.addView(ll);

                                        } catch (Exception e) {
                                            XposedBridge.log(e.getMessage());
                                        }
                                    }
                                }
                            });

                    XposedHelpers.findAndHookMethod(CLASSNAME_XPERIA_VOLUMESLIDERS, lpparam.classLoader, "hidePanel",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (mNotifSeekbarVolumizer != null && mNotifSeekbarVolumizer.isSamplePlaying())
                                        mNotifSeekbarVolumizer.stopSample();
                                }
                            });
                } catch (XposedHelpers.ClassNotFoundError e) {
                }
            }

            // Android 6.0 or higher
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    XposedHelpers.findClass(CLASSNAME_VOLUMEDIALOG, lpparam.classLoader);
                    XposedBridge.log("Class systemui.volume.VolumeDialog found");

                    final Class<?> classVolumeDialogController = XposedHelpers.findClass(
                            CLASSNAME_VOLUMEDIALOGCONTROLLER, lpparam.classLoader);
                    final Class<?> classZenModeController = XposedHelpers.findClass(CLASSNAME_ZENMODECONTROLLER,
                            lpparam.classLoader);
                    final Class<?> classCallback = XposedHelpers.findClass(CLASSNAME_CALLBACK, lpparam.classLoader);
                    final Class<?> classVolumeRow = XposedHelpers.findClass(CLASSNAME_VOLUMEROW, lpparam.classLoader);

                    XposedHelpers.findAndHookConstructor(CLASSNAME_VOLUMEDIALOG, lpparam.classLoader, Context.class,
                            int.class, classVolumeDialogController, classZenModeController, classCallback,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    XposedBridge.log("VolumeDialog:constructor");

                                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject,
                                            "mContext");
                                    int ringerDrawableId = context.getResources().getIdentifier(
                                            "ic_audio_notification", "drawable", "android");
                                    int ringerMuteDrawableId = context.getResources().getIdentifier(
                                            "ic_audio_notification_mute", "drawable", "android");
                                    XposedHelpers.callMethod(param.thisObject, "addRow", STREAM_NOTIFICATION,
                                            ringerDrawableId, ringerMuteDrawableId, true);
                                }

                            });

                    XposedHelpers.findAndHookMethod(CLASSNAME_VOLUMEDIALOG, lpparam.classLoader, "updateVolumeRowH", classVolumeRow,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    //XposedBridge.log("VolumeDialog:updateVolumeRowH");
                                    String notifLabel = Build.VERSION.SDK_INT >= VERSION_CODES_N ? "Notification" : "Notification volume";
                                    String ringLabel = Build.VERSION.SDK_INT >= VERSION_CODES_N ? "Ring" : "Ring volume";

                                    int stream = (int) XposedHelpers.getObjectField(param.args[0], "stream");
                                    TextView tv = (TextView) XposedHelpers.getObjectField(param.args[0], "header");
                                    if (stream == STREAM_NOTIFICATION)
                                        tv.setText(notifLabel);
                                    else if (stream == STREAM_RINGER)
                                        tv.setText(ringLabel);
                                }

                            });
                } catch (ClassNotFoundError e) {
                    XposedBridge.log("Class systemui.volume.VolumeDialog not found");
                } catch (NoSuchMethodError e1) {
                    XposedBridge.log("Method systemui.volume.VolumeDialog.updateVolumeRowH not found");
                }
            }
        } else if (lpparam.packageName.equals(PKGNAME_SYSTEM_SERVICE)) {
            // post Marshmallow implementation
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    final Class<?> classAudioService = XposedHelpers.findClass(CLASSNAME_AUDIO_SERVICE_MM,
                            lpparam.classLoader);

                    findAndHookMethod(classAudioService, "updateStreamVolumeAlias", boolean.class, String.class,
                            new XC_MethodHook() {
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    // get existing volume alias values
                                    int[] streamVolumeAlias = (int[]) getObjectField(param.thisObject,
                                            "mStreamVolumeAlias");

                                    // unlink notification stream from ring
                                    // stream
                                    streamVolumeAlias[STREAM_NOTIFICATION] = STREAM_NOTIFICATION;
                                    XposedHelpers.setObjectField(param.thisObject, "mStreamVolumeAlias",
                                            streamVolumeAlias);
                                }

                                ;
                            });
                } catch (ClassNotFoundError e) {
                    XposedBridge.log(e.getMessage());
                }
            }
        }
    }
}
