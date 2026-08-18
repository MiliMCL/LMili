package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.HotReloadUnsupported;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

import java.security.SecureRandom;
import java.util.Base64;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "secure_seed")
public class SecureSeedConfig implements IConfigModule {
    @TransformedConfig(name = "enabled", directory = {"misc", "secure_seed"})
    @ConfigInfo(name = "enabled", comments = """
                     Once you enable secure seed, all ores and structures are generated with 1024-bit seed
                     instead of using 64-bit seed in vanilla, making traditional seed cracking impossible.
            Note: If you use V1 it will be vulnerable to terrain elevation attacks.
                     ***** WARN: You need keep it enabled if your old world are also using secure seed! Or it will kill your save *****""")
    @HotReloadUnsupported
    public static boolean enabled = false;

    @ConfigInfo(name = "version", comments = """
            Version 1: Blake2b (insecure, reversible with a GPU/ASIC cluster in minutes with enough entropy)
            Version 2: Blake3 with salt key derivation (recommended, irreversible)
            ***** WARN: Switching versions will cause chunk errors! *****""")
    @HotReloadUnsupported
    public static int version = 1;

    @ConfigInfo(name = "salt", comments = """
            Auto-generated 256-bit salt for V2 cryptographic operations.
            Generated once on first startup - DO NOT SHARE THIS OR MODIFY (MODIFYING THIS WILL CAUSE CHUNK ERRORS)!
            Used with Blake3 keyed hash to make seed irreversible.""")
    @HotReloadUnsupported
    public static String salt = generateSalt();

    private static String generateSalt() {
        byte[] saltBytes = new byte[32];
        new SecureRandom().nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }
}
