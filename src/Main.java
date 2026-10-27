import java.io.File;
import java.util.Scanner;

public class Main {

    // ---------------- Configuración ----------------
    static final double ALPHA = 0.1, ALPHA_MIN = 0.01, GAMMA = 0.99, EPS_INICIAL = 1.0, EPS_MIN = 0.01;
    static final int EPISODIOS_RECOMENDADOS = 15000;   // más episodios sobreajustan a los niveles de entrenamiento
    static final int SEMILLA_QL = 1234;          // semilla del generador aleatorio del agente (exploración)
    static final int SEMILLA_ENTRENO = 100;      // la partida i de entrenamiento usa SEMILLA_ENTRENO + i
    static final int SEMILLA_TEST = 1000;
    static final int[] NIVELES_ENTRENO = {0, 1, 2};
    static final int[] NIVELES_TEST = {0, 1, 2, 3, 4};

    static final Scanner entrada = new Scanner(System.in);

    public static void main(String[] args) {
        QLearning ql = new QLearning(ALPHA, GAMMA, EPS_INICIAL, SEMILLA_QL);
        Agente.ql = ql;

        boolean salir = false;
        while (!salir) {
            mostrarMenu();
            int opcion = leerEntero("Opcion: ", 0, 6);
            try {
                switch (opcion) {
                    case 1: entrenar(ql); break;
                    case 2: test(ql); break;
                    case 3: testUnitario(ql); break;
                    case 4: pintarTabla(ql); break;
                    case 5: guardarTabla(ql); break;
                    case 6: cargarTabla(ql); break;
                    case 0: salir = true; break;
                }
            } catch (Exception e) {
                System.out.println("Error: " + e);
            }
        }
        System.exit(0);
    }

    static void mostrarMenu() {
        System.out.println();
        System.out.println("===== Q-LEARNING LUNAR LANDER =====");
        System.out.println(" 1. Entrenar");
        System.out.println(" 2. Test (varias partidas por nivel)");
        System.out.println(" 3. Test unitario (una partida con graficos)");
        System.out.println(" 4. Pintar la tabla");
        System.out.println(" 5. Guardar la tabla");
        System.out.println(" 6. Cargar una tabla");
        System.out.println(" 0. Salir");
    }

    // ---------------- Opciones ----------------
    static void entrenar(QLearning ql) {
        int episodios = leerEntero("Numero de episodios (recomendado " + EPISODIOS_RECOMENDADOS + "): ", 1, Integer.MAX_VALUE);
        int cada = leerEntero("Mostrar una partida cada cuantos episodios (-1 = nunca): ", -1, Integer.MAX_VALUE);
        Entrenador.entrenar(ql, episodios, NIVELES_ENTRENO, cada, EPS_INICIAL, EPS_MIN, ALPHA, ALPHA_MIN, SEMILLA_ENTRENO);
    }

    static void test(QLearning ql) {
        int partidas = leerEntero("Partidas por nivel: ", 1, Integer.MAX_VALUE);
        Entrenador.test(ql, partidas, NIVELES_TEST, -1, SEMILLA_TEST);
    }

    static void testUnitario(QLearning ql) {
        int nivel = leerEntero("Nivel (0-4): ", 0, 4);
        Entrenador.testUnitario(ql, nivel, SEMILLA_TEST);
    }

    static void pintarTabla(QLearning ql) {
        int visitados = 0;
        int[] mejores = new int[QLearning.NUM_ACCIONES];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int s = 0; s < QLearning.NUM_ESTADOS; s++) {
            if (!ql.estadoVisitado(s)) continue;
            visitados++;
            mejores[ql.mejorAccion(s)]++;
            for (int a = 0; a < QLearning.NUM_ACCIONES; a++) {
                min = Math.min(min, ql.valor(s, a));
                max = Math.max(max, ql.valor(s, a));
            }
        }
        System.out.printf("Estados totales: %d | visitados: %d (%.2f%%)%n",
                QLearning.NUM_ESTADOS, visitados, 100.0 * visitados / QLearning.NUM_ESTADOS);
        if (visitados == 0) {
            System.out.println("La tabla esta vacia (todo a 0).");
            return;
        }
        System.out.printf("Valores Q entre %.2f y %.2f%n", min, max);
        System.out.print("Mejor accion en los estados visitados:");
        for (int a = 0; a < QLearning.NUM_ACCIONES; a++)
            System.out.printf("  %s=%d", QLearning.NOMBRES_ACCION[a], mejores[a]);
        System.out.println();

        int n = leerEntero("Cuantos estados visitados quieres listar (0 = ninguno): ", 0, visitados);
        int mostrados = 0;
        for (int s = 0; s < QLearning.NUM_ESTADOS && mostrados < n; s++) {
            if (!ql.estadoVisitado(s)) continue;
            mostrados++;
            System.out.printf("%6d | %s | Q[NIL %.2f, UP %.2f, LEFT %.2f, RIGHT %.2f] -> %s%n", s,
                    Cerebro.describirEstado(s), ql.valor(s, 0), ql.valor(s, 1), ql.valor(s, 2), ql.valor(s, 3),
                    QLearning.NOMBRES_ACCION[ql.mejorAccion(s)]);
        }
    }

    static void guardarTabla(QLearning ql) throws Exception {
        String nombre = leerNombre("Nombre del fichero (sin .csv): ");
        if (new File(QLearning.CARPETA, nombre + ".csv").exists()
                && !leerLinea("Ya existe, sobrescribir? (s/n): ").equalsIgnoreCase("s")) {
            System.out.println("Cancelado.");
            return;
        }
        ql.guardar(nombre);
    }

    static void cargarTabla(QLearning ql) throws Exception {
        File[] ficheros = new File(QLearning.CARPETA).listFiles((d, n) -> n.endsWith(".csv"));
        if (ficheros == null || ficheros.length == 0) {
            System.out.println("No hay tablas en la carpeta " + QLearning.CARPETA + "/");
            return;
        }
        java.util.Arrays.sort(ficheros);
        System.out.println("Tablas disponibles:");
        for (int i = 0; i < ficheros.length; i++) System.out.println("  " + (i + 1) + ". " + ficheros[i].getName());

        String texto = leerLinea("Numero o nombre de la tabla: ");
        String nombre;
        try {
            int idx = Integer.parseInt(texto);
            if (idx < 1 || idx > ficheros.length) {
                System.out.println("Numero fuera de rango.");
                return;
            }
            nombre = ficheros[idx - 1].getName();
        } catch (NumberFormatException e) {
            nombre = texto;
        }
        if (nombre.endsWith(".csv")) nombre = nombre.substring(0, nombre.length() - 4);
        ql.cargar(nombre);
    }

    // ---------------- Entrada por teclado ----------------
    static String leerLinea(String mensaje) {
        System.out.print(mensaje);
        if (!entrada.hasNextLine()) {          // entrada cerrada: salir limpiamente
            System.out.println();
            System.exit(0);
        }
        return entrada.nextLine().trim();
    }

    static String leerNombre(String mensaje) {
        while (true) {
            String n = leerLinea(mensaje);
            if (n.endsWith(".csv")) n = n.substring(0, n.length() - 4);
            if (n.matches("[A-Za-z0-9_\\-]+")) return n;
            System.out.println("Usa solo letras, numeros, guion y guion bajo.");
        }
    }

    static int leerEntero(String mensaje, int min, int max) {
        while (true) {
            String t = leerLinea(mensaje);
            try {
                int v = Integer.parseInt(t);
                if (v >= min && v <= max) return v;
            } catch (NumberFormatException ignorado) { }
            System.out.println("Introduce un entero" + (max == Integer.MAX_VALUE ? " >= " + min : " entre " + min + " y " + max) + ".");
        }
    }
}