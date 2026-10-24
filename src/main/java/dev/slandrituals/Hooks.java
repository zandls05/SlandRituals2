package dev.slandrituals;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/** Integraciones opcionales (MythicMobs y PlaceholderAPI) mediante reflexión: no son dependencias obligatorias. */
public final class Hooks {

    private final SlandRitualsPlugin plugin;
    private Method papiSet;

    public Hooks(SlandRitualsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean mythicAvailable() {
        return plugin.getConfig().getBoolean("integrations.mythicmobs", true)
                && Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    /** Invoca un mob de MythicMobs. Devuelve null si no se pudo (el llamador usa la alternativa vanilla). */
    public Entity spawnMythic(String mobId, Location loc) {
        try {
            Class<?> cls = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = cls.getMethod("inst").invoke(null);
            Object helper = cls.getMethod("getAPIHelper").invoke(inst);
            Object res = helper.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                    .invoke(helper, mobId, loc);
            return res instanceof Entity e ? e : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            plugin.getLogger().warning("No se pudo invocar el mob de MythicMobs '" + mobId + "': " + t);
            return null;
        }
    }

    public boolean bossAvailable() {
        return plugin.getConfig().getBoolean("integrations.boss", true)
                && Bukkit.getPluginManager().isPluginEnabled("Boss");
    }

    /** ClassLoader del plugin Boss (MineAcademy), o null. */
    private ClassLoader bossLoader() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("Boss");
        return pl == null ? null : pl.getClass().getClassLoader();
    }

    private static Object call(Object o, String name) throws ReflectiveOperationException {
        if (o == null) return null;
        Method m = o.getClass().getMethod(name);
        m.trySetAccessible();
        return m.invoke(o);
    }

    /**
     * Invoca un jefe del plugin Boss de MineAcademy por su nombre (BossAPI.getBoss(nombre).spawn(ubicacion, CUSTOM)).
     * Todo por reflexión, así que Boss no es una dependencia obligatoria. Devuelve null si falla.
     */
    public Entity spawnBoss(String name, Location loc) {
        try {
            ClassLoader cl = bossLoader();
            if (cl == null) return null;
            Class<?> api = Class.forName("org.mineacademy.boss.api.BossAPI", true, cl);
            Object boss = api.getMethod("getBoss", String.class).invoke(null, name);
            if (boss == null) {
                plugin.getLogger().warning("El plugin Boss no tiene ningún jefe llamado '" + name + "' (revisa /boss menu).");
                return null;
            }
            Method spawn = null;
            Object reason = null;
            for (Method m : boss.getClass().getMethods()) {
                if (!m.getName().equals("spawn")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Location.class && p[1].isEnum()) {
                    for (Object c : p[1].getEnumConstants()) if (c.toString().equals("CUSTOM")) reason = c;
                    if (reason != null) { spawn = m; break; }
                } else if (p.length == 1 && p[0] == Location.class && spawn == null) {
                    spawn = m;
                }
            }
            if (spawn == null) {
                plugin.getLogger().warning("No se encontró el método spawn(Location) en la API de Boss.");
                return null;
            }
            spawn.trySetAccessible();
            Object res = reason != null ? spawn.invoke(boss, loc, reason) : spawn.invoke(boss, loc);
            Object spawned = res;
            if (res != null && res.getClass().getName().endsWith("Tuple")) {
                Object key = call(res, "getKey");
                if (key != null && !"SUCCESS".equals(key.toString())) {
                    plugin.getLogger().warning("Boss no pudo invocar '" + name + "': " + key);
                    return null;
                }
                spawned = call(res, "getValue");
            }
            if (spawned instanceof Entity e) return e;
            Object ent = call(spawned, "getEntity");
            return ent instanceof Entity e ? e : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            plugin.getLogger().warning("No se pudo invocar el jefe '" + name + "' del plugin Boss: " + t);
            return null;
        }
    }

    /** true si la entidad es un jefe del plugin Boss (para no borrar sus drops propios). */
    public boolean isBossEntity(Entity entity) {
        if (!bossAvailable()) return false;
        try {
            ClassLoader cl = bossLoader();
            if (cl == null) return false;
            Class<?> api = Class.forName("org.mineacademy.boss.api.BossAPI", true, cl);
            Object r = api.getMethod("isBoss", Entity.class).invoke(null, entity);
            return r instanceof Boolean b && b;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Resuelve placeholders de PlaceholderAPI si está instalado; si no, devuelve el texto igual. */
    public String parse(Player p, String text) {
        if (p == null || text == null || text.indexOf('%') < 0) return text;
        if (!plugin.getConfig().getBoolean("integrations.placeholderapi", true)) return text;
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return text;
        try {
            if (papiSet == null) {
                papiSet = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", Player.class, String.class);
            }
            Object r = papiSet.invoke(null, p, text);
            return r == null ? text : r.toString();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return text;
        }
    }
}
