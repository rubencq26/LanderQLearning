import core.game.Observation;
import core.game.StateObservation;
import ontology.Types;
import tools.Vector2d;

import java.util.ArrayList;
import java.util.Random;


public class Cerebro {
    //Constantes
    //Ángulo
    private static final double PASO_GIRO = 0.2; // 0.2 radianes por pulsacion
    private static final int GIRO_VERTICAL = 8; //8 pulsaciones para girar la nave verticalmente
    private int giro = 0;

    //Velocidad deseada
    private static final double V_MAX = 4; //px/tick
    private static final double V_MIN = 3.5; //px/tick
    private static final double DECEL = 0.5; //px/tick^2
    private static final double K_REP = 2; // Cerca de una pared se syma un empuje que la aleja de ella
    private static final int RADIO_REP = 10; //px

    //Discretizacion
    private static final double[] angLevels = {-3.5, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5}; // dGiro, un valor por giro hasta ±3 -> 9
    private static final double[] exLevels  = {-3, -1.5, -0.6, -0.2, 0.2, 0.6, 1.5, 3};    // vx - vx deseada, px/tick -> 9
    private static final double[] eyLevels  = {-3, -1.5, -0.6, -0.2, 0.2, 0.6, 1.5, 3};    // vy - vy deseada, px/tick -> 9

    //Mapa y Objetivos
    private static final int MIRA = 8;
    private static final int [] MARGENES = {-6, 2};
    private static final int ANCHO_LATERAL = 5;
    private static final double PROB_SUBOBJETIVO = 0.5;
    private static final int PESO_V = 2;
    private static final int PESO_ANG = 2;

    //Atributos
    //Mapa
    private final double blockSize;
    private final int ancho;
    private final int alto;
    private final boolean [][] pared;
    private final ArrayList<Vector2d> paredesPx;

    //Objetivo
    private final Vector2d aterrizaje;
    private final double[][] distanciaPad;

    private Vector2d destino;
    private double[][] distancia; // distancia geodésica de cada celda al destino
    private double distanciaExtra; // del subobjetivo al pad (0 si el destino ya es el pad)

    private boolean espejo;

    private static class Grupo {
        int fila, x0, x1;
        int columnasLibres;                        // columnas del pad con 3 celdas libres encima
        Grupo(int fila, int x0, int x1) { this.fila = fila; this.x0 = x0; this.x1 = x1; }
    }

    public Cerebro(StateObservation obs, boolean aleatorio, Random rnd){
        blockSize = obs.getBlockSize();
        ancho = (int) Math.round(obs.getWorldDimension().width / blockSize);
        alto = (int) Math.round(obs.getWorldDimension().height / blockSize);
        pared = new boolean[ancho][alto];
        paredesPx = new ArrayList<>();
        for (Observation o : obs.getImmovablePositions()[0]){
            paredesPx.add(new Vector2d(o.position.x, o.position.y));
            int cx = celda(o.position.x), cy = celda(o.position.x);
            if(dentro(cx, cy)) pared[cx][cy] = true;
        }

        boolean[][] pad = new boolean[ancho][alto];
        for (Observation o : obs.getImmovablePositions()[1]){
            int cx = celda(o.position.x), cy = celda(o.position.y);
            if(dentro(cx, cy)) pad[cx][cy] = true;
        }

        ArrayList<Grupo> grupos = agrupar(pad);
        Grupo g = aleatorio ? grupos.get(rnd.nextInt(grupos.size())) : elegirGrupo(grupos, obs.getAvatarPosition());

        aterrizaje = new Vector2d((g.x0 + g.x1) / 2.0 * blockSize, (g.fila - 1) * blockSize);
        distanciaPad = dijkstra(g, 0, 0);
        irAlPad();
        if (aleatorio && rnd.nextDouble() < PROB_SUBOBJETIVO) elegirSubobjetivo(obs.getAvatarPosition(), rnd);
    }

    private void irAlPad(){
        destino = aterrizaje;
        distancia = distanciaPad;
        distanciaExtra = 0;
    }

    private void elegirSubobjetivo(Vector2d pos, Random rnd){
        ArrayList<int[]> candidatas = new ArrayList<>();
        for(int x = 1; x < ancho - 1; x++)
            for(int y = 1; y < alto - 1; y++)
                if(!pegadaAPared(x, y) && libre(x,y) && distanciaPad[x][y] < Double.MAX_VALUE
                    && Math.hypot(x * blockSize - pos.x, y * blockSize - pos.y) > 3 * blockSize)
                    candidatas.add(new int[]{x, y});
        if(candidatas.isEmpty()) return;
        int[] c = candidatas.get(rnd.nextInt(candidatas.size()));
        destino = new Vector2d(c[0] * blockSize, c[1] * blockSize);
        distancia = dijkstra(null, c[0], c[1]);
        distanciaExtra = distanciaPad[c[0]][c[1]];
    }

    private void actualizarDestino(Vector2d pos){
        if(destino != aterrizaje && Math.hypot(destino.x - pos.x, destino.y - pos.y) < 0.7 * blockSize) irAlPad();
    }

    // Rotacion
    public void setRotacion(Types.ACTIONS action){
        if (action == Types.ACTIONS.ACTION_LEFT) giro++;
        if (action == Types.ACTIONS.ACTION_RIGHT) giro--;
    }

    private double dGiro(){
        double dosPi = 2 * Math.PI;
        double d = PASO_GIRO * (giro - GIRO_VERTICAL);
        d = ((d % dosPi) + dosPi) % dosPi;
        if (d > Math.PI) d -= dosPi;
        return d / PASO_GIRO;
    }

    public boolean espejo(){
        return espejo;
    }

    private double[] velocidadDeseada(Vector2d pos){
        Vector2d wp = waypoint(pos);
        double ex = wp.x - pos.x, ey = wp.y - pos.y, d = Math.hypot(ex, ey);
        if (d == 0) return new double[]{0, 0};
        double v = Math.max(V_MIN, Math.min(V_MAX, Math.sqrt(2 * DECEL * d)));
        double[] rep = repulsion(pos);
        return new double[]{ex / d * v + rep[0], ey / d * v + rep[1]};
    }

    private double[] repulsion(Vector2d pos){
        double rx = 0, ry = 0;
        for (Vector2d w: paredesPx){
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

    public int getNumEstados() {
        return numEstados();
    }

    public static int numEstados() {
        return producto(tamanos());
    }

    public int getState(StateObservation obs){
        Vector2d pos = obs.getAvatarPosition();
        actualizarDestino(pos);
        double[] vd = velocidadDeseada(pos);

        double rapidez = obs.getAvatarSpeed();
        double ex = obs.getAvatarOrientation().x * rapidez - vd[0];
        double ey = obs.getAvatarOrientation().y * rapidez - vd[1];
        double ang = dGiro();

        espejo = vd[0] < 0;
        if (espejo){
            ex = -ex;
            ang = -ang;
        }

        int[] valores = {bin(ang, angLevels), bin(ey, eyLevels), bin(ex, exLevels)};
        int[] tam = tamanos();

        int indice = 0;
        for(int i = 0; i < tam.length; i++){
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

    private static int bin(double valor, double[] limites) {
        int i = 0;
        while (i < limites.length && valor >= limites[i]) i++;
        return i;
    }

    public double potencial(StateObservation obs) {
        Vector2d pos = obs.getAvatarPosition();
        double[] vd = velocidadDeseada(pos);
        double rapidez = obs.getAvatarSpeed();
        double errorV = Math.hypot(obs.getAvatarOrientation().x * rapidez - vd[0],
                obs.getAvatarOrientation().y * rapidez - vd[1]);
        double inclinacion = Math.abs(dGiro()) * PASO_GIRO;
        return -distanciaGeodesica(pos) - PESO_V * errorV - PESO_ANG * inclinacion;
    }


    private int celda(double px){
        return (int) Math.round(px / blockSize);
    }

    private boolean dentro(int x, int y){
        return x >= 0 && y >= 0 && x < ancho && y < alto;
    }

    private boolean libre(int x, int y){
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



}
