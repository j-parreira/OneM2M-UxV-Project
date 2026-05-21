/**
 * {@code PIDController} — Controlador PID para navegação de drone.
 *
 * Usa um controlador proporcional-integral-derivativo para calcular
 * o pitch de voo necessário para navegar até um alvo a uma distância conhecida.
 *
 * <p>Parâmetros por defeito:
 * <ul>
 *   <li>{@code kp = 0.2} — resposta proporcional à distância</li>
 *   <li>{@code ki = 0.0001} — corrige erro acumulado (drift)</li>
 *   <li>{@code kd = 0.2} — amortecimento baseado na variação do erro</li>
 *   <li>Pitch max = ±15° (limite de segurança)</li>
 * </ul>
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.flight;

/**
 * Controlador PID para navegação de drone.
 *
 * <p>Recebe a distância atual ao alvo e a velocidade atual do drone,
 * e calcula o pitch necessário para chegar ao alvo de forma suave.
 *
 * <h3>Fórmula PID</h3>
 * <pre>
 * pitch = Kp × error + Ki × integral + Kd × derivative
 * pitch = max(-15°, min(15°, pitch))
 * </pre>
 *
 * <h3>Aceleração mínima</h3>
 * <p>Quando a velocidade é zero, adiciona um offset de 3° para
 * iniciar o movimento (evita ficar parado em distâncias > 2m).
 */
public class PIDController {

    /** Ponto alvo do controlador (sempre 0 para navegação — queremos zero erro). */
    private double setpoint = 0;

    /** Ganho proporcional — resposta à distância atual ao alvo. */
    private double kp;

    /** Ganho integral — corrige erro acumulado ao longo do tempo. */
    private double ki;

    /** Ganho derivativo — amortecimento baseado na variação do erro. */
    private double kd;

    /** Erro integral acumulado. */
    private double integral;

    /** Erro anterior (para cálculo derivativo). */
    private double previousError;

    /** Pitch máximo em graus (limite de segurança). */
    private double maxPitch = 15;

    /**
     * Cria um controlador PID com os ganhos fornecidos.
     *
     * @param kp ganho proporcional (resposta à distância)
     * @param ki ganho integral (corrige drift)
     * @param kd ganho derivativo (amortecimento)
     */
    public PIDController(double kp, double ki, double kd) {
        this.setpoint = 0;
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
        this.integral = 0;
        this.previousError = 0;
    }

    /**
     * Calcula o pitch de voo para chegar ao alvo.
     *
     * <p>Lógica:
     * <ol>
     *   <li>Se distância < 2m: pitch = 0 (parar)</li>
     *   <li>Se velocidade = 0: add offset de aceleração (3°)</li>
     *   <li>PID: Kp×error + Ki×integral + Kd×derivative</li>
     *   <li>Limitar a ±15°</li>
     * </ol>
     *
     * @param currentDistance a distância atual ao alvo (metros)
     * @param velocity a velocidade atual do drone (m/s)
     * @return o pitch em graus (-15 a 15)
     */
    public double calculateThrottle(double currentDistance, double velocity) {
        double error = currentDistance - setpoint;

        // Parar se muito perto do alvo (< 2m)
        if (currentDistance < 2) {
            return 0;
        }

        // Componentes PID
        double proportional = kp * error;
        integral += ki * error;
        double derivative = kd * (error - previousError);

        // Pitch resultante
        double pitch = proportional + integral + derivative;

        // Limitar a ±15°
        pitch = Math.max(-maxPitch, Math.min(maxPitch, pitch));

        // Quando velocidade é zero, adiciona aceleração mínima para iniciar o movimento
        if (velocity == 0) {
            double acceleration = 3;
            pitch += acceleration;
        }

        // Reaplicar limites (pois a aceleração pode ter excedido)
        pitch = Math.max(-15, Math.min(15, pitch));

        previousError = error;

        return pitch;
    }
}
