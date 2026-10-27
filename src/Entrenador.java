import ontology.Types;
import tracks.ArcadeMachine;

import java.awt.Window;
import java.io.OutputStream;
import java.io.PrintStream;

public class Entrenador {
    static final String JUEGO = "examples/contphysics/lander.txt";
    static final String AGENTE = "Agente";
    static final int PASO_BARRA = 100;
    static final int ANCHO_BARRA = 30;
    static final int PAUSA_FINAL_MS = 1000;

    static String rutaNivel(int nivel){
        return "examples/contphysics/lander_lvl" + nivel + ".txt";
    }

    public static class Estadisticas {
        final int[] niveles;
        final int[] victorias, derrotas, tiempos;
        long ticks;
        double recompensa;

        Estadisticas(int[] niveles){
            this.niveles = niveles;
            victorias = new int[niveles.length];
            derrotas = new int[niveles.length];
            tiempos = new int[niveles.length];
        }

        void registrar(int idxNivel, double[] resultado, double recompensaEpisodio){
            int w = (int) resultado[0];
            if(w == Types.WINNER.PLAYER_WINS.key()) victorias[idxNivel]++;
            else if(w == Types.WINNER.PLAYER_LOSES.key()) derrotas[idxNivel]++;
            else tiempos[idxNivel]++;
            ticks += (long) resultado[2];
            recompensa += recompensaEpisodio;
        }

        int total(){
            int t = 0;
            for (int i = 0; i < niveles.length; i++) t += victorias[i] + derrotas[i] + tiempos[i];
            return t;
        }

        void imprimir(String titulo) {
            int v = 0, d = 0, t = 0;
            System.out.println("== " + titulo + " ==");
            for (int i = 0; i < niveles.length; i++) {
                int n = victorias[i] + derrotas[i] + tiempos[i];
                if (n == 0) continue;
                System.out.printf("  Nivel %d: %d partidas | victorias %d (%.1f%%) | derrotas %d | tiempo agotado %d%n",
                        niveles[i], n, victorias[i], 100.0 * victorias[i] / n, derrotas[i], tiempos[i]);
                v += victorias[i]; d += derrotas[i]; t += tiempos[i];
            }
            int n = v + d + t;
            if (n > 0)
                System.out.printf("  TOTAL:   %d partidas | victorias %d (%.1f%%) | derrotas %d (%.1f%%) | tiempo agotado %d (%.1f%%) | ticks medios %.0f | recompensa media %.2f%n",
                        n, v, 100.0 * v / n, d, 100.0 * d / n, t, 100.0 * t / n, (double) ticks / n, recompensa / n);
        }
    }

    // ---------------- Una partida ----------------
    private static double[] jugar(int nivel, boolean visual, int semilla) {
        PrintStream real = System.out;
        if (!visual) System.setOut(new PrintStream(new OutputStream() { public void write(int b) { } }));
        try {
            return ArcadeMachine.runOneGame(JUEGO, rutaNivel(nivel), visual, AGENTE, null, semilla, 0);
        } finally {
            System.setOut(real);
            if (visual) cerrarVentanas();
        }
    }

    /**
     * El motor solo cierra la ventana al terminar si juega un humano, así que con un agente
     * quedaría abierta para siempre. La cerramos nosotros tras una breve pausa para ver el final.
     */
    private static void cerrarVentanas() {
        try {
            Thread.sleep(PAUSA_FINAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Window w : Window.getWindows()) w.dispose();
    }

    /** Partida de demostración: greedy, sin actualizar la tabla, con gráficos. */
    private static void demostracion(int nivel, int semilla, String etiqueta) {
        boolean modoAnterior = Agente.entrenando;
        Agente.entrenando = false;
        double[] r = jugar(nivel, true, semilla);
        Agente.entrenando = modoAnterior;
        String res = r[0] == Types.WINNER.PLAYER_WINS.key() ? "victoria"
                : r[0] == Types.WINNER.PLAYER_LOSES.key() ? "derrota" : "tiempo agotado";
        System.out.printf("   [demo %s, nivel %d] %s en %d ticks%n", etiqueta, nivel, res, (int) r[2]);
    }

    // ---------------- Entrenamiento ----------------
    /**
     * @param episodios   nº de partidas de entrenamiento
     * @param niveles     niveles que se juegan, en rotación (p. ej. {0,1,2})
     * @param mostrarCada cada cuántos episodios se enseña una partida de demostración y se da el informe parcial (-1 = desactivado)
     * @param epsInicial  exploración al empezar
     * @param epsMin      exploración mínima (se alcanza al 80 % del entrenamiento, decayendo de forma exponencial)
     * @param alphaInicial tasa de aprendizaje al empezar
     * @param alphaMin    tasa de aprendizaje al acabar (decae de forma exponencial durante todo el entrenamiento,
     *                    para que los valores Q se asienten y la política final sea estable)
     * @param semillaBase la semilla de la partida i es semillaBase + i
     */
    public static Estadisticas entrenar(QLearning ql, int episodios, int[] niveles, int mostrarCada,
                                        double epsInicial, double epsMin, double alphaInicial, double alphaMin,
                                        int semillaBase) {
        Agente.ql = ql;
        Agente.entrenando = true;
        double decay = Math.pow(epsMin / epsInicial, 1.0 / (0.8 * episodios));
        double decayAlpha = Math.pow(alphaMin / alphaInicial, 1.0 / episodios);
        Estadisticas total = new Estadisticas(niveles);
        Estadisticas bloque = new Estadisticas(niveles);
        boolean conBarra = mostrarCada <= 0;          // sin demostraciones periódicas: se muestra la barra de progreso
        int victoriasTramo = 0;                       // victorias desde el último repintado de la barra

        for (int ep = 0; ep < episodios; ep++) {
            int idx = ep % niveles.length;
            ql.epsilon = Math.max(epsMin, epsInicial * Math.pow(decay, ep));
            ql.alpha = alphaInicial * Math.pow(decayAlpha, ep);
            double[] r = jugar(niveles[idx], false, semillaBase + ep);
            total.registrar(idx, r, Agente.recompensaEpisodio);
            bloque.registrar(idx, r, Agente.recompensaEpisodio);
            if (r[0] == Types.WINNER.PLAYER_WINS.key()) victoriasTramo++;

            if (conBarra && ((ep + 1) % PASO_BARRA == 0 || ep + 1 == episodios)) {
                int tramo = (ep + 1) % PASO_BARRA == 0 ? PASO_BARRA : (ep + 1) % PASO_BARRA;
                pintarBarra(ep + 1, episodios, victoriasTramo, tramo, ql.epsilon);
                victoriasTramo = 0;
            }

            if (mostrarCada > 0 && (ep + 1) % mostrarCada == 0) {
                bloque.imprimir(String.format("Entrenamiento: episodios %d-%d (epsilon %.3f)", ep + 2 - mostrarCada, ep + 1, ql.epsilon));
                demostracion(niveles[idx], semillaBase + ep, "ep " + (ep + 1));
                bloque = new Estadisticas(niveles);
            }
        }
        if (conBarra) System.out.println();           // salta de la línea de la barra
        total.imprimir("Entrenamiento: resumen de " + episodios + " episodios");
        return total;
    }

    /** Barra de progreso en una sola línea (\r vuelve al inicio y la sobrescribe). */
    private static void pintarBarra(int hechos, int total, int victoriasTramo, int tramo, double epsilon) {
        int llenos = (int) ((long) ANCHO_BARRA * hechos / total);
        StringBuilder sb = new StringBuilder("\r[");
        for (int i = 0; i < ANCHO_BARRA; i++) sb.append(i < llenos ? '=' : i == llenos ? '>' : ' ');
        int digitos = String.valueOf(total).length();
        sb.append(String.format("] %3d%%  %" + digitos + "d/%d episodios | victorias (ultimos %d): %3.0f%% | epsilon %.3f",
                (int) (100L * hechos / total), hechos, total, tramo, 100.0 * victoriasTramo / tramo, epsilon));
        System.out.print(sb);
        System.out.flush();
    }

    // ---------------- Test ----------------
    /**
     * Política greedy, sin exploración y sin actualizar la tabla.
     * @param partidasPorNivel nº de partidas en cada nivel
     * @param mostrarCada      cada cuántas partidas se juega una con gráficos (-1 = desactivado)
     */
    public static Estadisticas test(QLearning ql, int partidasPorNivel, int[] niveles, int mostrarCada, int semillaBase) {
        Agente.ql = ql;
        Agente.entrenando = false;
        Estadisticas total = new Estadisticas(niveles);
        int contador = 0;
        for (int idx = 0; idx < niveles.length; idx++) {
            for (int k = 0; k < partidasPorNivel; k++) {
                contador++;
                boolean visual = mostrarCada > 0 && contador % mostrarCada == 0;
                double[] r = jugar(niveles[idx], visual, semillaBase + k);
                total.registrar(idx, r, Agente.recompensaEpisodio);
            }
        }
        total.imprimir("Test");
        return total;
    }

    // ---------------- Test unitario ----------------
    /** Una sola partida con gráficos, en modo greedy y sin actualizar la tabla. */
    public static void testUnitario(QLearning ql, int nivel, int semilla) {
        Agente.ql = ql;
        Agente.entrenando = false;
        double[] r = jugar(nivel, true, semilla);
        String res = r[0] == Types.WINNER.PLAYER_WINS.key() ? "VICTORIA"
                : r[0] == Types.WINNER.PLAYER_LOSES.key() ? "DERROTA" : "TIEMPO AGOTADO";
        System.out.printf("Test unitario, nivel %d: %s | puntuacion %.1f | %d ticks | recompensa acumulada %.2f%n",
                nivel, res, r[1], (int) r[2], Agente.recompensaEpisodio);
    }


}
