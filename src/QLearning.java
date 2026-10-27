import java.io.*;
import java.util.Random;

/**
 * Tabla Q y algoritmo Q-learning. No depende de GVGAI: solo guarda valores,
 * elige acciones (epsilon-greedy) y aplica la actualización.
 */
public class QLearning {

    public static final int NUM_ESTADOS = Cerebro.numEstados();   // siempre coincide con la discretización de Cerebro
    public static final int NUM_ACCIONES = 4;         // 0 NIL, 1 UP, 2 LEFT, 3 RIGHT
    public static final String CARPETA = "table";
    public static final String[] NOMBRES_ACCION = {"NIL", "UP", "LEFT", "RIGHT"};

    private final float[][] Q = new float[NUM_ESTADOS][NUM_ACCIONES];
    public double alpha;      // tasa de aprendizaje
    public double gamma;      // factor de descuento
    public double epsilon;    // probabilidad de explorar
    private final Random rnd;

    public QLearning(double alpha, double gamma, double epsilon, int seed) {
        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;
        this.rnd = new Random(seed);
    }

    /** epsilon-greedy si explorar = true; greedy puro si no. Los empates se resuelven al azar. */
    public int elegirAccion(int s, boolean explorar) {
        if (explorar && rnd.nextDouble() < epsilon) {
            return rnd.nextInt(NUM_ACCIONES);
        }
        int mejor = 0, empates = 1;
        for (int a = 1; a < NUM_ACCIONES; a++) {
            if (Q[s][a] > Q[s][mejor]) {
                mejor = a;
                empates = 1;
            } else if (Q[s][a] == Q[s][mejor] && rnd.nextInt(++empates) == 0) {
                mejor = a;
            }
        }
        return mejor;
    }

    public double maxQ(int s) {
        double m = Q[s][0];
        for (int a = 1; a < NUM_ACCIONES; a++) m = Math.max(m, Q[s][a]);
        return m;
    }

    /** Q(s,a) += alpha * (r + gamma * max Q(s') - Q(s,a)) */
    public void actualizar(int s, int a, double r, int sSiguiente) {
        Q[s][a] += alpha * (r + gamma * maxQ(sSiguiente) - Q[s][a]);
    }

    /** Último paso del episodio: no hay estado siguiente, el objetivo es solo r. */
    public void actualizarTerminal(int s, int a, double r) {
        Q[s][a] += alpha * (r - Q[s][a]);
    }

    // ---------------- Guardar / cargar en CSV: table/<nombre>.csv ----------------
    // Una fila por estado (la fila i es el estado i) y una columna por acción.

    public void guardar(String nombre) throws IOException {
        File carpeta = new File(CARPETA);
        if (!carpeta.exists()) carpeta.mkdirs();
        File fichero = new File(carpeta, nombre + ".csv");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(fichero))) {
            w.write(String.join(",", NOMBRES_ACCION));
            w.newLine();
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < NUM_ESTADOS; s++) {
                sb.setLength(0);
                for (int a = 0; a < NUM_ACCIONES; a++) {
                    if (a > 0) sb.append(',');
                    sb.append(Q[s][a]);            // Float.toString usa siempre punto decimal
                }
                w.write(sb.toString());
                w.newLine();
            }
        }
        System.out.println("Tabla guardada en " + fichero.getPath());
    }

    public void cargar(String nombre) throws IOException {
        File fichero = new File(CARPETA, nombre + ".csv");
        try (BufferedReader r = new BufferedReader(new FileReader(fichero))) {
            r.readLine();                           // cabecera
            for (int s = 0; s < NUM_ESTADOS; s++) {
                String linea = r.readLine();
                if (linea == null)
                    throw new IOException("La tabla tiene menos de " + NUM_ESTADOS + " filas: " + fichero.getPath());
                String[] partes = linea.split(",");
                if (partes.length != NUM_ACCIONES)
                    throw new IOException("Fila " + s + " con " + partes.length + " columnas (se esperaban " + NUM_ACCIONES + ")");
                for (int a = 0; a < NUM_ACCIONES; a++) Q[s][a] = Float.parseFloat(partes[a]);
            }
            if (r.readLine() != null)
                throw new IOException("La tabla tiene más filas de las esperadas: la discretización no coincide");
        }
        System.out.println("Tabla cargada de " + fichero.getPath());
    }

    // ---------------- Consulta (para pintar la tabla) ----------------
    public float valor(int s, int a) {
        return Q[s][a];
    }

    /** Un estado está visitado si alguno de sus valores es distinto de 0 (la tabla empieza a 0). */
    public boolean estadoVisitado(int s) {
        for (int a = 0; a < NUM_ACCIONES; a++) if (Q[s][a] != 0) return true;
        return false;
    }

    /** Número de estados visitados (con algún valor distinto de 0). */
    public int estadosLlenos() {
        int llenos = 0;
        for (int s = 0; s < NUM_ESTADOS; s++) if (estadoVisitado(s)) llenos++;
        return llenos;
    }

    /** Porcentaje (0-100) de estados visitados sobre el total. */
    public double porcentajeLlenado() {
        return 100.0 * estadosLlenos() / NUM_ESTADOS;
    }

    /** Imprime el llenado de la tabla Q: total de estados, llenos y porcentaje. */
    public void mostrarLlenado() {
        int llenos = estadosLlenos();
        System.out.printf("Llenado de la tabla Q: %d / %d estados llenos (%.2f%%)%n",
                llenos, NUM_ESTADOS, 100.0 * llenos / NUM_ESTADOS);
    }

    /** Mejor acción sin aleatoriedad (en empate, la de menor índice). */
    public int mejorAccion(int s) {
        int mejor = 0;
        for (int a = 1; a < NUM_ACCIONES; a++) if (Q[s][a] > Q[s][mejor]) mejor = a;
        return mejor;
    }
}