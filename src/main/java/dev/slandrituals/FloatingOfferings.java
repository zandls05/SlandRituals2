package dev.slandrituals;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;

/**
 * Objeto mágico suspendido sobre el centro exacto del altar durante la invocación.
 *
 * <ul>
 *   <li>Levita con una oscilación senoidal suave y gira despacio alrededor del eje Y, a la vez.</li>
 *   <li>La posición se calcula en cada fotograma desde una referencia fija (el centro del altar) y el
 *       número de fotograma: nada se acumula, así que no puede haber deriva.</li>
 *   <li>Usa una única entidad {@link ItemDisplay} que solo se mueve; no se crean entidades nuevas.</li>
 *   <li>En los últimos fotogramas cae sobre el altar: el impacto despierta al jefe.</li>
 * </ul>
 *
 * Lo avanza {@link Ceremony} con su tarea de siempre (un fotograma cada 2 ticks, 10 por segundo).
 */
final class FloatingOfferings {

    /** Altura de reposo del objeto sobre la cara superior del altar (bloques). */
    private static final double HOVER = 1.3;
    /** Amplitud de la levitación: sube y baja esta distancia desde la altura de reposo (bloques). */
    private static final double BOB_AMPLITUDE = 0.12;
    /** Velocidad de la levitación en radianes por fotograma (0,10 por tick): un ciclo cada ~3 s. */
    private static final double BOB_SPEED = 0.20;
    /** Giro en grados por fotograma (60 grados por segundo): una vuelta completa cada 6 s. */
    private static final double SPIN_DEGREES = 6.0;
    private static final float SCALE = 0.6f;

    private final SlandRitualsPlugin plugin;
    private final World world;
    private final Material material;
    private final Player only;
    /** Referencia fija: centro del altar en X y Z. El objeto nunca se aparta de ella. */
    private final double anchorX;
    private final double anchorZ;
    /** Altura a la que el objeto reposa sobre el altar y altura de levitación. */
    private final double restY;
    private final double hoverY;
    private ItemDisplay display;
    private boolean created;
    private boolean finished;

    /**
     * @param offerings objetos de la ofrenda; se muestra el primero válido
     * @param only      si no es null, solo ese jugador ve el objeto
     */
    FloatingOfferings(SlandRitualsPlugin plugin, Location center, Collection<Material> offerings, Player only) {
        this.plugin = plugin;
        this.world = center.getWorld();
        this.anchorX = center.getX();
        this.anchorZ = center.getZ();
        this.restY = center.getY() + 0.5 + 0.2; // justo sobre la cara superior del bloque
        this.hoverY = restY + HOVER;
        this.only = only;
        Material first = null;
        for (Material m : offerings) {
            if (m.isItem() && !m.isAir()) {
                first = m;
                break;
            }
        }
        this.material = first;
    }

    /**
     * Avanza un fotograma.
     *
     * @param frame fotograma desde que empezó la invocación
     * @param total fotogramas totales hasta que aparece el jefe
     * @return true en el fotograma en que el objeto toca el altar
     */
    boolean update(int frame, int total) {
        if (finished || material == null) return false;
        if (!created) create();
        if (display == null) {
            finished = true;
            return false;
        }
        if (!display.isValid()) return false; // chunk descargado o entidad retirada: no se duplica nada

        int rise = Math.min(10, Math.max(1, total / 3)); // fotogramas de ascenso inicial
        int drop = Math.min(8, Math.max(1, total / 3));  // fotogramas de caída final
        int dropStart = total - drop;

        // Levitación: función periódica del fotograma, siempre medida desde hoverY (sin acumulación).
        double target = hoverY + Math.sin(frame * BOB_SPEED) * BOB_AMPLITUDE;

        double y;
        boolean landing = false;
        if (frame >= dropStart) { // caída: se funde la levitación con la altura del altar
            double t = Math.min(1.0, (frame - dropStart + 1) / (double) drop);
            double s = t * t;     // acelera como si cayera
            y = (1.0 - s) * target + s * restY;
            landing = t >= 1.0;
        } else if (frame < rise) { // ascenso suave desde el altar hasta la levitación
            double t = (frame + 1) / (double) rise;
            double ease = 1.0 - (1.0 - t) * (1.0 - t);
            y = restY + (target - restY) * ease;
        } else {                   // levitación
            y = target;
        }

        // Rotación continua e independiente de la altura: yaw lineal con el fotograma.
        float yaw = (float) (((frame * SPIN_DEGREES + 180.0) % 360.0) - 180.0);

        display.teleport(new Location(world, anchorX, y, anchorZ, yaw, 0f));

        if (landing) {
            finished = true;
            remove();
            return true;
        }
        return false;
    }

    private void create() {
        created = true;
        try {
            ItemDisplay d = world.spawn(new Location(world, anchorX, restY, anchorZ), ItemDisplay.class);
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED); // gira con la entidad, no mira al jugador
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2); // el cliente interpola posición y giro entre fotogramas
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(SCALE, SCALE, SCALE), new AxisAngle4f()));
            d.setPersistent(false);   // nunca se guarda en el mundo
            if (only != null) {
                d.setVisibleByDefault(false);
                if (only.isOnline()) only.showEntity(plugin, d);
            }
            display = d;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No se pudo crear el objeto flotante: " + ex.getMessage());
        }
    }

    /** Elimina el objeto flotante. Se puede llamar varias veces. */
    void remove() {
        if (display != null) {
            if (display.isValid()) display.remove();
            display = null;
        }
    }
}
