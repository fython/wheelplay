package moe.feng.wheelplay.carlife;

import java.util.*;

/** Candidate components, not a claim of device compatibility. TCP remains package-independent. */
public final class CarLifeBrands {
    public static final class Profile {
        public final String id, label, enginePackage, hostPackage, entryPackage, entryClass, mixingAction;
        public final List<String> mixingClasses;
        public final boolean tcpVerified;
        Profile(String id, String label, String engine, String host, String entryPackage, String entryClass,
                String action, boolean verified, String... mixing) {
            this.id=id; this.label=label; enginePackage=engine; hostPackage=host;
            this.entryPackage=entryPackage; this.entryClass=entryClass; mixingAction=action;
            tcpVerified=verified; mixingClasses=Collections.unmodifiableList(Arrays.asList(mixing));
        }
    }
    public static final Profile GENERIC = new Profile("generic", "通用手动连接", "", "", "", "", null, false);
    public static final List<Profile> PROFILES = Collections.unmodifiableList(Arrays.asList(
        new Profile("vivo", "vivo 智能车载", "com.baidu.carlife.vivo", "com.vivo.car.networking",
            "com.vivo.carlauncher", "com.vivo.connectguide.activity.connect.CarLifeCarWifiConnectActivity", null, true,
            "com.vivo.vivo_car_service.CarLifeMixingService"),
        new Profile("xiaomi", "小米 CarWith", "com.baidu.carlife.xiaomi", "com.miui.carlink",
            "com.miui.carlink", "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity", null, false,
            "com.xiaomi.ucar.carlife.CarlifeConnService"),
        new Profile("oppo", "OPPO 车机互联", "com.baidu.carlife.oppo", "com.oplus.ocar", "", "",
            "com.oplus.ocar.action.START_CARLIFE_SERVICE", false, "com.oplus.ocar.connect.carlife.CarlifeCastManagerService"),
        new Profile("samsung", "三星车联", "com.baidu.carlife.samsung", "com.samsung.android.carlink", "", "", null, false,
            "com.samsung.android.carlink.mixing.CarLifeMixingService", "com.samsung.android.carlink.carlife2.CarLifeMixingService"),
        new Profile("honor", "荣耀车联", "com.baidu.carlife.honor", "com.hihonor.auto", "", "", null, false)
    ));
    public static Profile select(Set<String> visiblePackages, String manufacturer) {
        List<Profile> candidates = new ArrayList<>();
        for (Profile p : PROFILES) if (visiblePackages.contains(p.hostPackage) || visiblePackages.contains(p.enginePackage)) candidates.add(p);
        if (candidates.size() == 1) return candidates.get(0);
        for (Profile p : candidates) if (p.id.equalsIgnoreCase(manufacturer)) return p;
        return GENERIC; // No package or ambiguous candidates: do not guess a working brand.
    }
    private CarLifeBrands() {}
}
