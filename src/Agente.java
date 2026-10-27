import core.game.StateObservation;
import core.player.AbstractPlayer;
import ontology.Types;
import ontology.Types.ACTIONS;
import tools.ElapsedCpuTimer;

import java.util.Random;

/**
 * El motor crea un Agente NUEVO en cada partida, así que todo lo que debe sobrevivir
 * entre episodios (la tabla Q, el modo, la recompensa acumulada) es static.
 */
public class Agente extends AbstractPlayer {

    // ---- Compartido entre episodios (lo configura el Entrenador antes de cada partida) ----
    public static QLearning ql;
    public static boolean entrenando = true;       // true: epsilon-greedy y actualiza Q | false: greedy, sin actualizar
    public static double recompensaEpisodio;       // recompensa acumulada de la última partida
    public static Random rnd = new Random(42);     // elige el pad objetivo al azar durante el entrenamiento

    // ---- Recompensas ----
    static final double R_VICTORIA = 100, R_CHOQUE = -100, R_TIEMPO = -20, R_TICK = -0.05;

    // Acciones internas -> acciones del juego (el orden fija las columnas de la tabla)
    static final ACTIONS[] ACCIONES = {
            ACTIONS.ACTION_NIL, ACTIONS.ACTION_UP, ACTIONS.ACTION_LEFT, ACTIONS.ACTION_RIGHT };
    // La misma acción vista en el estado espejado (LEFT <-> RIGHT)
    static final int[] ESPEJO = {0, 1, 3, 2};
    static final double PROB_GIRO_INICIAL = 0.25;
    static final double PROB_AZAR_INICIAL = 0.5;
    static final int MAX_TICKS_AZAR = 25;
    static final int MAX_TICKS_GIRO = 16;

    // ---- Estado de esta partida ----
    private final Cerebro cerebro;
    private int sPrev = -1, aPrev = -1;
    private double phiPrev;
    // Entrenando, algunos episodios empiezan girando la nave unos ticks hacia un lado, y otros
    // con unos ticks de acciones al azar: así se ven orientaciones y velocidades variadas
    // (p. ej. apuntando al lado contrario del pad, o cayendo demasiado rápido), que en los
    // niveles 0-2 casi no aparecen pero en otros mapas sí.
    private int ticksForzados = 0;
    private ACTIONS giroForzado = ACTIONS.ACTION_NIL;

    public Agente(StateObservation stateObs, ElapsedCpuTimer elapsedTimer) {
        cerebro = new Cerebro(stateObs, entrenando, rnd);
        recompensaEpisodio = 0;
        double p = entrenando ? rnd.nextDouble() : 1;
        if (p < PROB_GIRO_INICIAL) {
            ticksForzados = 1 + rnd.nextInt(MAX_TICKS_GIRO);
            giroForzado = rnd.nextBoolean() ? ACTIONS.ACTION_LEFT : ACTIONS.ACTION_RIGHT;
        } else if (p < PROB_GIRO_INICIAL + PROB_AZAR_INICIAL) {
            ticksForzados = 1 + rnd.nextInt(MAX_TICKS_AZAR);
            giroForzado = null;                    // null = una acción al azar en cada tick
        }
    }

    @Override
    public ACTIONS act(StateObservation stateObs, ElapsedCpuTimer elapsedTimer) {
        int s = cerebro.getState(stateObs);
        double phi = cerebro.potencial(stateObs);

        // 1. Cierra la transición del tick anterior: (sPrev, aPrev) -> r -> s
        if (sPrev >= 0) {
            double r = R_TICK + ql.gamma * phi - phiPrev;
            recompensaEpisodio += r;
            if (entrenando) ql.actualizar(sPrev, aPrev, r, s);
        }

        // 2. Elige la acción nueva (en el estado espejado si toca) y recuerda el estado para el próximo tick
        int a = ql.elegirAccion(s, entrenando);
        if (ticksForzados > 0) {
            ticksForzados--;
            if (giroForzado == null) a = rnd.nextInt(ACCIONES.length);
            else {
                a = giroForzado == ACTIONS.ACTION_LEFT ? 2 : 3;
                if (cerebro.espejo()) a = ESPEJO[a];
            }
        }
        ACTIONS accion = ACCIONES[cerebro.espejo() ? ESPEJO[a] : a];
        cerebro.setRotacion(accion);
        sPrev = s;
        aPrev = a;
        phiPrev = phi;
        return accion;
    }

    /** El motor la llama al terminar la partida: aquí se cierra el último paso. */
    @Override
    public void result(StateObservation stateObs, ElapsedCpuTimer elapsedCpuTimer) {
        Types.WINNER ganador = stateObs.getGameWinner();
        double r = ganador == Types.WINNER.PLAYER_WINS ? R_VICTORIA
                : ganador == Types.WINNER.PLAYER_LOSES ? R_CHOQUE
                : R_TIEMPO;
        recompensaEpisodio += r;
        if (entrenando && sPrev >= 0) ql.actualizarTerminal(sPrev, aPrev, r);
    }
}