import java.awt.*;
import java.io.*;
import java.util.Random;

public class QLearning {
    private static int NUM_ESTADOS;
    private static final int NUM_ACCIONES = 4;
    private float [][]Q;


    private static final String CARPETA = "table";
    public static final String[] NOMBRES_ACCION = {"NIL", "UP", "LEFT", "RIGHT"};

    public double alpha;
    public double gamma;
    public double epsilon;

    public Random rnd;

    public QLearning(double alpha, double gamma, double epsilon, int seed) {
        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;
        this.rnd = new Random(seed);
    }

    public int elegirAccion(int s, boolean explorar){
        if(explorar && rnd.nextDouble() < epsilon){
            return rnd.nextInt(NUM_ACCIONES);
        }

        float mejorValor = Float.MIN_VALUE;
        int mejorAccion = -1;
        int empatadas = 0;

        for(int i = 0; i < NUM_ACCIONES; i++){
            if(Q[s][i] > mejorValor){
                mejorValor = Q[s][i];
                mejorAccion = i;
                empatadas = 1;
            }else if(Q[s][i] == mejorValor){
                empatadas++;
                if(rnd.nextDouble() < (double)(1 / empatadas)){
                    mejorAccion = i;
                }
            }
        }
        return mejorAccion;
    }

    public float maxQ(int s){
        float maximo =  Float.MIN_VALUE;

        for(int i = 0; i < NUM_ACCIONES; i++){
            if(Q[s][i] > maximo){
                maximo = Q[s][i];
            }
        }
        return maximo;
    }

    public void actualizar(int s, int a, int r, int sSig){
        Q[s][a] = (float)(Q[s][a] + alpha * (r + gamma * maxQ(sSig) - Q[s][a]));
    }

    public void actualizarTerminal(int s, int a, int r){
        Q[s][a] = (float)(Q[s][a] + alpha * (r - Q[s][a]));
    }

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

    public float valor(int s, int a){
        return Q[s][a];
    }

    public boolean estadoVisitado(int s){
        for (int a = 0; a < NUM_ACCIONES; a++) if (Q[s][a] != 0) return true;
        return false;
    }

    public int mejorAccion(int s){
        int mejor = 0;
        for (int a = 1; a < NUM_ACCIONES; a++) if (Q[s][a] > Q[s][mejor]) mejor = a;
        return mejor;
    }

}

