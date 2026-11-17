package moe.feng.wheelplay.carlife;

/** AUTO is resolved by recovery; each socket attempt uses one explicit protocol. */
public enum CarLifeProtocolMode {
    AUTO, LEGACY_3_0, XIAOMI_4_1;

    public static CarLifeProtocolMode forBrand(String brandId) {
        return "xiaomi".equals(brandId) ? XIAOMI_4_1 : LEGACY_3_0;
    }
}
