public class Cerebro {
    //Ángulo
    private final double PASO_GIRO = 0.2;
    private final int GIRO_VERTICAL = 8;

    //Velocidad deseada
    private final double V_MAX = 4;
    private final double V_MIN = 3.5;
    private final double DECEL = 0.5;
    private final double K_REP = 2;
    private final int RADIO_REP = 10;

    //Discretizacion
    private final double [] angLevels = {-3.5, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5};
    private final double [] exLevels = {-3.5, -1.5, -0.6, -0.2, 0.2, 0.6, 1.5, 3};

    //Mapa y Objetivos
    public final int MIRA = 8;



    public Cerebro(){

    }

}
