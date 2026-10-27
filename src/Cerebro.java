import core.game.Observation;
import core.game.StateObservation;
import ontology.Types;
import tools.Vector2d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Traduce una observación del juego a un índice de estado de la tabla Q.
 *
 * Ideas clave para que lo aprendido en los niveles 0-2 sirva en los niveles 3-4:
 *  - Objetivo fijo por partida: un GRUPO de pads (celdas contiguas), apuntando a su centro.
 *  - Waypoints: un Dijkstra sobre el mapa da el camino hasta el pad esquivando paredes, y se
 *    apunta al punto más lejano de ese camino que se ve en línea recta.
 *  - Velocidad deseada: hacia el waypoint, más lenta cuanto más cerca. El estado NO lleva la
 *    posición, sino el error de velocidad (real - deseada) y el ángulo. Así el agente aprende
 *    "sigue la velocidad deseada sin perder la vertical", que no depende del mapa.
 *  - Simetría: si el waypoint está a la izquierda, se espeja el estado (error en x y ángulo)
 *    y se intercambian LEFT/RIGHT. Lo aprendido yendo a la derecha sirve yendo a la izquierda.
 *  - Entrenando: pad objetivo al azar y, a veces, un subobjetivo al azar antes del pad, para
 *    practicar situaciones (subir, ir de lado...) que en los niveles 0-2 casi no se dan.
 */
public class Cerebro {

    // ---------- Ángulo ----------
    // El motor gira 0.2 rad por pulsación y la nave empieza apuntando a la derecha (rotación 0).
    // giro = nº de LEFT - nº de RIGHT. Ángulo "clásico" = 0.2 * giro (0 = derecha, antihorario).
    // El motor solo deja aterrizar si la rotación está en (4.4, 5.0) rad, que en "giros" son
    // exactamente 7, 8 y 9: tomamos 8 como la vertical y medimos dGiro = giro - 8.
    private static final double PASO_GIRO = 0.2;
    private static final int GIRO_VERTICAL = 8;
    private int giro = 0;

    // ---------- Velocidad deseada ----------
    private static final double V_MAX = 4;          // px/tick
    // El motor mueve la nave (int) velocidad píxeles por tick: por debajo de ~2 px/tick se queda
    // atascada. Y para aterrizar basta ir a menos de 10 px/tick. Así que nunca se pide ir más lento.
    private static final double V_MIN = 3.5;        // px/tick
    private static final double DECEL = 0.05;       // px/tick²
    // Cerca de una pared (a menos de RADIO_REP px) se suma un empuje que aleja de ella
    private static final double K_REP = 2;          // px/tick
    private static final double RADIO_REP = 10;     // px

    // ---------- Límites de discretización (estado ya espejado: el waypoint nunca está a la izquierda) ----------
    private static final double[] angLevels = {-3.5, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5}; // dGiro, un valor por giro hasta ±3 -> 9
    private static final double[] exLevels  = {-3, -1.5, -0.6, -0.2, 0.2, 0.6, 1.5, 3};    // vx - vx deseada, px/tick -> 9
    private static final double[] eyLevels  = {-3, -1.5, -0.6, -0.2, 0.2, 0.6, 1.5, 3};    // vy - vy deseada, px/tick -> 9

    // ---------- Mapa ----------
    private final double blockSize;
    private final int ancho, alto;                 // en celdas
    private final boolean[][] pared;               // [x][y]
    private final ArrayList<Vector2d> paredesPx;   // esquinas de las paredes, en píxeles

    // ---------- Objetivo ----------
    private final Vector2d aterrizaje;             // posición (esquina sup. izq.) de la nave posada en el centro del pad
    private final double[][] distanciaPad;         // distancia geodésica (en celdas) de cada celda al pad

    // Destino actual: el pad o, entrenando, a veces antes un subobjetivo al azar (una celda libre)
    // para practicar el control en situaciones que en los niveles 0-2 no hacen falta (p. ej. subir).
    private static final double PROB_SUBOBJETIVO = 0.5;
    private Vector2d destino;
    private double[][] distancia;                  // distancia geodésica de cada celda al destino
    private double distanciaExtra;                 // del subobjetivo al pad (0 si el destino ya es el pad)
    private static final int ANCHO_LATERAL = 5;    // columnas a cada lado del pad donde se penaliza llegar de lado
    private static final int MIRA = 8;             // cuántas celdas del camino se miran para buscar el waypoint

    // ---------- Resultado del último getState ----------
    private boolean espejo;

    /** Grupo de pads contiguos en la misma fila. */
    private static class Grupo {
        int fila, x0, x1;
        int columnasLibres;                        // columnas del pad con 3 celdas libres encima
        Grupo(int fila, int x0, int x1) { this.fila = fila; this.x0 = x0; this.x1 = x1; }
    }

    /**
     * @param aleatorio true (entrenando): el pad objetivo se elige al azar entre todos y, a veces,
     *                  antes se pasa por un subobjetivo al azar. false: se va directo al pad más
     *                  fácil (ver elegirGrupo).
     */
    public Cerebro(StateObservation obs, boolean aleatorio, Random rnd) {
        blockSize = obs.getBlockSize();
        ancho = (int) Math.round(obs.getWorldDimension().width / blockSize);
        alto = (int) Math.round(obs.getWorldDimension().height / blockSize);
        pared = new boolean[ancho][alto];
        paredesPx = new ArrayList<>();
        for (Observation o : obs.getImmovablePositions()[0]) {
            paredesPx.add(new Vector2d(o.position.x, o.position.y));
            int cx = celda(o.position.x), cy = celda(o.position.y);
            if (dentro(cx, cy)) pared[cx][cy] = true;
        }

        boolean[][] pad = new boolean[ancho][alto];
        for (Observation o : obs.getImmovablePositions()[1]) {
            int cx = celda(o.position.x), cy = celda(o.position.y);
            if (dentro(cx, cy)) pad[cx][cy] = true;
        }
        ArrayList<Grupo> grupos = agrupar(pad);
        Grupo g = aleatorio ? grupos.get(rnd.nextInt(grupos.size())) : elegirGrupo(grupos, obs.getAvatarPosition());

        aterrizaje = new Vector2d((g.x0 + g.x1) / 2.0 * blockSize, (g.fila - 1) * blockSize);
        distanciaPad = dijkstra(g, 0, 0);
        irAlPad();
        if (aleatorio && rnd.nextDouble() < PROB_SUBOBJETIVO) elegirSubobjetivo(obs.getAvatarPosition(), rnd);
    }

    private void irAlPad() {
        destino = aterrizaje;
        distancia = distanciaPad;
        distanciaExtra = 0;
    }

    /** Una celda libre al azar, con sus 8 vecinas libres, alcanzable y no pegada a la nave. */
    private void elegirSubobjetivo(Vector2d pos, Random rnd) {
        ArrayList<int[]> candidatas = new ArrayList<>();
        for (int x = 1; x < ancho - 1; x++)
            for (int y = 1; y < alto - 1; y++)
                if (!pegadaAPared(x, y) && libre(x, y) && distanciaPad[x][y] < Double.MAX_VALUE
                        && Math.hypot(x * blockSize - pos.x, y * blockSize - pos.y) > 3 * blockSize)
                    candidatas.add(new int[]{x, y});
        if (candidatas.isEmpty()) return;
        int[] c = candidatas.get(rnd.nextInt(candidatas.size()));
        destino = new Vector2d(c[0] * blockSize, c[1] * blockSize);
        distancia = dijkstra(null, c[0], c[1]);
        distanciaExtra = distanciaPad[c[0]][c[1]];
    }

    /** Si se ha llegado al subobjetivo, el destino pasa a ser el pad. */
    private void actualizarDestino(Vector2d pos) {
        if (destino != aterrizaje && Math.hypot(destino.x - pos.x, destino.y - pos.y) < 0.7 * blockSize) irAlPad();
    }

    // =====================================================================
    //  Rotación
    // =====================================================================
    public void setRotacion(Types.ACTIONS action) {
        if (action == Types.ACTIONS.ACTION_LEFT) giro++;
        if (action == Types.ACTIONS.ACTION_RIGHT) giro--;
    }

    /** Giros respecto a la vertical, en (-π, π] pasado a giros. Positivo = inclinado a la izquierda. */
    private double dGiro() {
        double dosPi = 2 * Math.PI;
        double d = PASO_GIRO * (giro - GIRO_VERTICAL);
        d = ((d % dosPi) + dosPi) % dosPi;
        if (d > Math.PI) d -= dosPi;
        return d / PASO_GIRO;
    }

    /** true si en el último estado se espejó: hay que intercambiar LEFT y RIGHT. */
    public boolean espejo() {
        return espejo;
    }

    /**
     * Velocidad deseada {vx, vy} en px/tick: hacia el waypoint, frenando al acercarse (sin bajar
     * de V_MIN), más un empuje que aleja de las paredes muy cercanas.
     */
    private double[] velocidadDeseada(Vector2d pos) {
        Vector2d wp = waypoint(pos);
        double ex = wp.x - pos.x, ey = wp.y - pos.y, d = Math.hypot(ex, ey);
        if (d == 0) return new double[]{0, 0};
        double v = Math.max(V_MIN, Math.min(V_MAX, Math.sqrt(2 * DECEL * d)));
        double[] rep = repulsion(pos);
        return new double[]{ex / d * v + rep[0], ey / d * v + rep[1]};
    }

    /** Empuje que aleja de las paredes a menos de RADIO_REP px, más fuerte cuanto más cerca. */
    private double[] repulsion(Vector2d pos) {
        double rx = 0, ry = 0;
        for (Vector2d w : paredesPx) {
            double gx = Math.max(0, Math.max(w.x - (pos.x + blockSize), pos.x - (w.x + blockSize)));
            double gy = Math.max(0, Math.max(w.y - (pos.y + blockSize), pos.y - (w.y + blockSize)));
            double gap = Math.hypot(gx, gy);
            if (gap >= RADIO_REP) continue;
            double cx = pos.x - w.x, cy = pos.y - w.y, n = Math.hypot(cx, cy);
            double f = K_REP * (1 - gap / RADIO_REP);
            rx += cx / n * f;
            ry += cy / n * f;
        }
        return new double[]{rx, ry};
    }

    /*
        VARIABLES DEL ESTADO (en este orden; el primero pesa más en el índice)

        #  Variable                                     Intervalos
        0  Ángulo respecto a la vertical (dGiro)        9  (un valor por giro hasta ±3; más allá, agrupado)
        1  Error de velocidad vertical (vy - vy des.)   9
        2  Error de velocidad horizontal (vx - vx des.) 9  (espejado: positivo = demasiado rápido hacia el waypoint)

        indice = (a0*n1 + a1)*n2 + a2
        Nº total de estados = 9*9*9 = 729
     */
    public int getNumEstados() {
        return numEstados();
    }

    public static int numEstados() {
        return producto(tamanos());
    }

    public int getState(StateObservation obs) {
        Vector2d pos = obs.getAvatarPosition();
        actualizarDestino(pos);
        double[] vd = velocidadDeseada(pos);

        double rapidez = obs.getAvatarSpeed();
        double ex = obs.getAvatarOrientation().x * rapidez - vd[0];
        double ey = obs.getAvatarOrientation().y * rapidez - vd[1];   // positivo = cae más rápido de lo deseado
        double ang = dGiro();

        espejo = vd[0] < 0;
        if (espejo) {
            ex = -ex;
            ang = -ang;
        }

        int[] valores = {bin(ang, angLevels), bin(ey, eyLevels), bin(ex, exLevels)};
        int[] tam = tamanos();

        int indice = 0;
        for (int i = 0; i < valores.length; i++) {
            indice = indice * tam[i] + valores[i];
        }
        return indice;
    }

    public static int[] tamanos() {
        return new int[]{angLevels.length + 1, eyLevels.length + 1, exLevels.length + 1};
    }

    private static int producto(int[] v) {
        int p = 1;
        for (int x : v) p *= x;
        return p;
    }

    // Número de límites que valor alcanza o supera = índice del intervalo (0..limites.length)
    private static int bin(double valor, double[] limites) {
        int i = 0;
        while (i < limites.length && valor >= limites[i]) i++;
        return i;
    }

    // =====================================================================
    //  Potencial (recompensa por acercarse), con tres términos:
    //   - distancia geodésica al pad (en celdas),
    //   - lo que se aleja la velocidad de la velocidad deseada,
    //   - la inclinación respecto a la vertical.
    // =====================================================================
    private static final double PESO_V = 2, PESO_ANG = 2;

    public double potencial(StateObservation obs) {
        Vector2d pos = obs.getAvatarPosition();
        double[] vd = velocidadDeseada(pos);
        double rapidez = obs.getAvatarSpeed();
        double errorV = Math.hypot(obs.getAvatarOrientation().x * rapidez - vd[0],
                obs.getAvatarOrientation().y * rapidez - vd[1]);
        double inclinacion = Math.abs(dGiro()) * PASO_GIRO;
        return -distanciaGeodesica(pos) - PESO_V * errorV - PESO_ANG * inclinacion;
    }

    // =====================================================================
    //  Mapa: grupos de pads, Dijkstra y waypoints
    // =====================================================================
    private int celda(double px) {
        return (int) Math.round(px / blockSize);
    }

    private boolean dentro(int x, int y) {
        return x >= 0 && y >= 0 && x < ancho && y < alto;
    }

    private boolean libre(int x, int y) {
        return dentro(x, y) && !pared[x][y];
    }

    private ArrayList<Grupo> agrupar(boolean[][] pad) {
        ArrayList<Grupo> grupos = new ArrayList<>();
        for (int y = 0; y < alto; y++) {
            int x = 0;
            while (x < ancho) {
                if (!pad[x][y]) { x++; continue; }
                int x0 = x;
                while (x < ancho && pad[x][y]) x++;
                Grupo g = new Grupo(y, x0, x - 1);
                for (int c = g.x0; c <= g.x1; c++)
                    if (libre(c, y - 1) && libre(c, y - 2) && libre(c, y - 3)) g.columnasLibres++;
                grupos.add(g);
            }
        }
        return grupos;
    }

    /** El pad más fácil: el que tiene más columnas despejadas encima; a igualdad, el más cercano. */
    private Grupo elegirGrupo(ArrayList<Grupo> grupos, Vector2d pos) {
        Grupo mejor = null;
        double mejorDist = Double.MAX_VALUE;
        for (Grupo g : grupos) {
            double d = Math.hypot((g.x0 + g.x1) / 2.0 - pos.x / blockSize, g.fila - pos.y / blockSize);
            if (mejor == null || g.columnasLibres > mejor.columnasLibres
                    || (g.columnasLibres == mejor.columnasLibres && d < mejorDist)) {
                mejor = g;
                mejorDist = d;
            }
        }
        return mejor;
    }

    /**
     * Distancia (en celdas) de cada celda libre a la celda de aterrizaje del grupo g (o, si g es
     * null, a la celda (sx, sy)), con 8 vecinos.
     * Las celdas pegadas a una pared cuestan más, para que el camino no roce las paredes
     * (salvo el pasillo justo encima del pad, que hay que atravesar para posarse), y también
     * las de las tres filas sobre el pad y hasta ANCHO_LATERAL columnas a sus lados: al pad se llega desde arriba.
     */
    private double[][] dijkstra(Grupo g, int sx, int sy) {
        double[][] d = new double[ancho][alto];
        for (double[] col : d) Arrays.fill(col, Double.MAX_VALUE);
        PriorityQueue<double[]> cola = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        if (g == null) {
            d[sx][sy] = 0;
            cola.add(new double[]{0, sx, sy});
        }
        else {
            for (int c = g.x0; c <= g.x1; c++) {
                if (libre(c, g.fila - 1)) {
                    d[c][g.fila - 1] = Math.abs(c - (g.x0 + g.x1) / 2.0);
                    cola.add(new double[]{d[c][g.fila - 1], c, g.fila - 1});
                }
            }
        }
        while (!cola.isEmpty()) {
            double[] e = cola.poll();
            int x = (int) e[1], y = (int) e[2];
            if (e[0] > d[x][y]) continue;
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++) {
                    if (ox == 0 && oy == 0) continue;
                    int nx = x + ox, ny = y + oy;
                    if (!libre(nx, ny)) continue;
                    if (ox != 0 && oy != 0 && (!libre(x + ox, y) || !libre(x, y + oy))) continue;
                    boolean pasillo = g != null && nx >= g.x0 && nx <= g.x1 && ny < g.fila && ny >= g.fila - 3;
                    boolean lateral = g != null && !pasillo && ny >= g.fila - 3                     // llegar al pad de lado (o desde abajo)
                            && nx >= g.x0 - ANCHO_LATERAL && nx <= g.x1 + ANCHO_LATERAL;
                    double coste = (ox != 0 && oy != 0 ? Math.sqrt(2) : 1) + (!pasillo && pegadaAPared(nx, ny) ? 1.5 : 0)
                            + (lateral ? 4 : 0);
                    if (d[x][y] + coste < d[nx][ny]) {
                        d[nx][ny] = d[x][y] + coste;
                        cola.add(new double[]{d[nx][ny], nx, ny});
                    }
                }
        }
        return d;
    }

    private boolean pegadaAPared(int x, int y) {
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 1; oy++)
                if (!libre(x + ox, y + oy)) return true;
        return false;
    }

    /** Celda alcanzable más cercana a la posición (la nave puede estar entre dos celdas o rozando una pared). */
    private int[] celdaDe(Vector2d pos) {
        int[] mejor = null;
        double mejorD = Double.MAX_VALUE;
        int cx = (int) Math.floor(pos.x / blockSize), cy = (int) Math.floor(pos.y / blockSize);
        for (int x = cx - 1; x <= cx + 2; x++)
            for (int y = cy - 1; y <= cy + 2; y++) {
                if (!libre(x, y) || distancia[x][y] == Double.MAX_VALUE) continue;
                double d = Math.hypot(x * blockSize - pos.x, y * blockSize - pos.y) / blockSize + 0.01 * distancia[x][y];
                if (d < mejorD) {
                    mejorD = d;
                    mejor = new int[]{x, y};
                }
            }
        return mejor;
    }

    private double distanciaGeodesica(Vector2d pos) {
        int[] c = celdaDe(pos);
        if (c == null) return distanciaExtra + Math.hypot(destino.x - pos.x, destino.y - pos.y) / blockSize;
        return distanciaExtra + distancia[c[0]][c[1]] + Math.hypot(c[0] * blockSize - pos.x, c[1] * blockSize - pos.y) / blockSize;
    }

    /**
     * Sigue el camino de Dijkstra desde la celda de la nave y devuelve el punto más lejano
     * (hasta MIRA celdas) al que se puede ir en línea recta sin tocar paredes.
     * Si ese punto es ya el pad, devuelve la posición exacta de aterrizaje.
     * Primero se exige un margen de seguridad; si pegada a una pared no se ve nada así, se
     * prueba sin margen y, en último caso, se toma el siguiente paso del camino.
     */
    private Vector2d waypoint(Vector2d pos) {
        int[] c = celdaDe(pos);
        if (c == null) return destino;
        ArrayList<Vector2d> camino = new ArrayList<>();
        int x = c[0], y = c[1];
        for (int paso = 0; paso < MIRA; paso++) {
            int bx = x, by = y;
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    if (libre(x + ox, y + oy) && distancia[x + ox][y + oy] < distancia[bx][by]) {
                        bx = x + ox;
                        by = y + oy;
                    }
            if (bx == x && by == y) break;         // ya en la celda de aterrizaje
            x = bx;
            y = by;
            camino.add(new Vector2d(x * blockSize, y * blockSize));
        }
        // Al pad solo se va en línea recta con margen: si no, se roza la pared que suele tener al lado
        if (lineaLibre(pos, destino, MARGENES[0])) return destino;
        for (double margen : MARGENES) {
            for (int i = camino.size() - 1; i >= 0; i--)
                if (lineaLibre(pos, camino.get(i), margen)) return camino.get(i);
        }
        return camino.isEmpty() ? destino : camino.get(0);
    }

    // Margen en píxeles: negativo = la nave se trata como algo más grande, por seguridad
    private static final double[] MARGENES = {-6, 2};

    /** ¿Puede la nave (un cuadrado de una celda) ir de a a b en línea recta sin tocar ninguna pared? */
    private boolean lineaLibre(Vector2d a, Vector2d b, double margen) {
        double len = Math.hypot(b.x - a.x, b.y - a.y);
        int pasos = Math.max(1, (int) Math.ceil(len / 4));
        for (int i = 0; i <= pasos; i++) {
            double x = a.x + (b.x - a.x) * i / pasos, y = a.y + (b.y - a.y) * i / pasos;
            for (Vector2d w : paredesPx)
                if (x + margen < w.x + blockSize && w.x < x + blockSize - margen
                        && y + margen < w.y + blockSize && w.y < y + blockSize - margen) return false;
        }
        return true;
    }

    // ---------- Decodificar un índice de estado (para depurar / pintar la tabla) ----------
    public static String describirEstado(int indice) {
        int[] tam = tamanos();
        int[] v = new int[tam.length];
        for (int i = tam.length - 1; i >= 0; i--) {          // proceso inverso a getState
            v[i] = indice % tam[i];
            indice /= tam[i];
        }
        return String.format("dGiro %-8s errVy %-8s errVx %-8s",
                rango(v[0], angLevels), rango(v[1], eyLevels), rango(v[2], exLevels));
    }

    private static String rango(int i, double[] limites) {
        if (i == 0) return "<" + num(limites[0]);
        if (i == limites.length) return ">=" + num(limites[limites.length - 1]);
        return num(limites[i - 1]) + ".." + num(limites[i]);
    }

    private static String num(double d) {
        double r = Math.round(d * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}