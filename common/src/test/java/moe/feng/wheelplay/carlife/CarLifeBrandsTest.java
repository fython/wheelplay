package moe.feng.wheelplay.carlife;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class CarLifeBrandsTest {
    @Test public void installedComponentsWinOverManufacturerAndUnknownRemainsManual() {
        assertEquals("vivo", CarLifeBrands.select(Set.of("com.vivo.car.networking"), "unknown").id);
        assertEquals("xiaomi", CarLifeBrands.select(Set.of("com.baidu.carlife.xiaomi"), "vivo").id);
        assertSame(CarLifeBrands.GENERIC, CarLifeBrands.select(Set.of(), "vivo"));
        assertSame(CarLifeBrands.GENERIC, CarLifeBrands.select(Set.of("com.vivo.car.networking", "com.miui.carlink"), "unknown"));
        assertEquals("vivo", CarLifeBrands.select(Set.of("com.vivo.car.networking", "com.miui.carlink"), "VIVO").id);
    }
    @Test public void unverifiedProfilesDoNotImplyMixingSupport() {
        CarLifeBrands.Profile honor = CarLifeBrands.select(Set.of("com.hihonor.auto"), "honor");
        assertFalse(honor.tcpVerified); assertTrue(honor.mixingClasses.isEmpty());
        assertEquals(2, CarLifeBrands.select(Set.of("com.samsung.android.carlink"), "samsung").mixingClasses.size());
    }
}
