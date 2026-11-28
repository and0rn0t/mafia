package mafiabrain;

import org.bukkit.plugin.java.JavaPlugin;

/** 플러그인 껍데기: Brain 클래스를 서버에 올려 Skript(skript-reflect)가 부를 수 있게 한다. 하는 일은 없다 */
public final class MafiaBrain extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("MafiaBrain ready (mafia bot world model)");
    }
}
