/**
 * {@code ModuleVerificationUtil} — Verificação de módulos DJI.
 *
 * Fornece métodos estáticos para verificar a disponibilidade de módulos
 * do drone (flight controller, camera, gimbal, RTK, etc.)
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;


import com.dji.sdk.duvops.app.App;

import androidx.annotation.Nullable;
import dji.common.product.Model;
import dji.sdk.accessory.AccessoryAggregation;
import dji.sdk.accessory.beacon.Beacon;
import dji.sdk.accessory.speaker.Speaker;
import dji.sdk.accessory.spotlight.Spotlight;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.flightcontroller.Simulator;
import dji.sdk.products.Aircraft;
import dji.sdk.products.HandHeld;

/**
 * Utilitário para verificar a disponibilidade de módulos e componentes DJI.
 *
 * <h3>Módulos verificados</h3>
 * <ul>
 *   <li>Produto base, Aircraft, HandHeld</li>
 *   <li>Camera, Gimbal, Playback, MediaManager</li>
 *   <li>FlightController, Compass, RTK</li>
 *   <li>RemoteController, AirLink, WiFi, Lightbridge, OcuSync</li>
 *   <li>AccessoryAggregation (Speaker, Beacon, Spotlight)</li>
 * </ul>
 */
public class ModuleVerificationUtil {

    /** Verifica se algum produto DJI está conectado. */
    public static boolean isProductModuleAvailable() {
        return (null != App.getProductInstance());
    }

    /** Verifica se o produto é um Aircraft (drone). */
    public static boolean isAircraft() {
        return App.getProductInstance() instanceof Aircraft;
    }

    /** Verifica se o produto é um HandHeld (dispositivo portátil). */
    public static boolean isHandHeld() {
        return App.getProductInstance() instanceof HandHeld;
    }

    /** Verifica se a câmera está disponível. */
    public static boolean isCameraModuleAvailable() {
        return isProductModuleAvailable() && (null != App.getProductInstance().getCamera());
    }

    /** Verifica se o gestor de playback está disponível. */
    public static boolean isPlaybackAvailable() {
        return isCameraModuleAvailable() && (null != App.getProductInstance()
                .getCamera().getPlaybackManager());
    }

    /** Verifica se o gestor de media está disponível. */
    public static boolean isMediaManagerAvailable() {
        return isCameraModuleAvailable() && (null != App.getProductInstance()
                .getCamera().getMediaManager());
    }

    /** Verifica se o controlador remoto está disponível. */
    public static boolean isRemoteControllerAvailable() {
        return isProductModuleAvailable() && isAircraft() && (null != App.getAircraftInstance()
                .getRemoteController());
    }

    /** Verifica se o controlador de voo está disponível. */
    public static boolean isFlightControllerAvailable() {
        return isProductModuleAvailable() && isAircraft() && (null != App.getAircraftInstance()
                .getFlightController());
    }

    /** Verifica se a bússola está disponível. */
    public static boolean isCompassAvailable() {
        return isFlightControllerAvailable() && isAircraft() && (null != App.getAircraftInstance()
                .getFlightController().getCompass());
    }

    /** Verifica se as limitações de voo estão disponíveis. */
    public static boolean isFlightLimitationAvailable() {
        return isFlightControllerAvailable() && isAircraft();
    }

    /** Verifica se o gimbal está disponível. */
    public static boolean isGimbalModuleAvailable() {
        return isProductModuleAvailable() && (null != App.getProductInstance().getGimbal());
    }

    /** Verifica se o AirLink está disponível. */
    public static boolean isAirlinkAvailable() {
        return isProductModuleAvailable() && (null != App.getProductInstance().getAirLink());
    }

    /** Verifica se a ligação WiFi está disponível. */
    public static boolean isWiFiLinkAvailable() {
        return isAirlinkAvailable() && (null != App.getProductInstance().getAirLink().getWiFiLink());
    }

    /** Verifica se a ligação Lightbridge está disponível. */
    public static boolean isLightbridgeLinkAvailable() {
        return isAirlinkAvailable() && (null != App.getProductInstance().getAirLink().getLightbridgeLink());
    }

    /** Verifica se a ligação OcuSync está disponível. */
    public static boolean isOcuSyncLinkAvailable() {
        return isAirlinkAvailable() && (null != App.getProductInstance().getAirLink().getOcuSyncLink());
    }

    /** Verifica se um payload está disponível. */
    public static boolean isPayloadAvailable() {
        return isProductModuleAvailable() && isAircraft() && (null != App.getAircraftInstance()
                .getPayload());
    }

    /** Verifica se o RTK está disponível. */
    public static boolean isRTKAvailable() {
        return isProductModuleAvailable() && isAircraft() && (null != App.getAircraftInstance()
                .getFlightController().getRTK());
    }

    /** Obtém o agregado de acessórios (speaker, beacon, spotlight). */
    public static AccessoryAggregation getAccessoryAggregation() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && null != aircraft.getAccessoryAggregation()) {
            return aircraft.getAccessoryAggregation();
        }
        return null;
    }

    /** Obtém o altifalante do drone. */
    public static Speaker getSpeaker() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && null != aircraft.getAccessoryAggregation() && null != aircraft.getAccessoryAggregation().getSpeaker()) {
            return aircraft.getAccessoryAggregation().getSpeaker();
        }
        return null;
    }

    /** Obtém o beacon do drone. */
    public static Beacon getBeacon() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && null != aircraft.getAccessoryAggregation() && null != aircraft.getAccessoryAggregation().getBeacon()) {
            return aircraft.getAccessoryAggregation().getBeacon();
        }
        return null;
    }

    /** Obtém o holofote do drone. */
    public static Spotlight getSpotlight() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && null != aircraft.getAccessoryAggregation() && null != aircraft.getAccessoryAggregation().getSpotlight()) {
            return aircraft.getAccessoryAggregation().getSpotlight();
        }
        return null;
    }

    /**
     * Obtém o simulador do drone.
     *
     * @return o simulador ou null se não disponível
     */
    @Nullable
    public static Simulator getSimulator() {
        Aircraft aircraft = App.getAircraftInstance();
        if (aircraft != null) {
            FlightController flightController = aircraft.getFlightController();
            if (flightController != null) {
                return flightController.getSimulator();
            }
        }
        return null;
    }

    /**
     * Obtém o controlador de voo do drone.
     *
     * @return o FlightController ou null se não disponível
     */
    @Nullable
    public static FlightController getFlightController() {
        Aircraft aircraft = App.getAircraftInstance();
        if (aircraft != null) {
            return aircraft.getFlightController();
        }
        return null;
    }

    /**
     * Verifica se o produto conectado é um Mavic 2 (Pro ou Zoom).
     *
     * @return {@code true} se é Mavic 2 Pro ou Mavic 2 Zoom
     */
    @Nullable
    public static boolean isMavic2Product() {
        BaseProduct baseProduct = App.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MAVIC_2_PRO || baseProduct.getModel() == Model.MAVIC_2_ZOOM;
        }
        return false;
    }

    /**
     * Verifica se o produto conectado é um Matrice 300 RTK.
     *
     * @return {@code true} se é Matrice 300 RTK
     */
    public static boolean isMatrice300RTK() {
        BaseProduct baseProduct = App.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MATRICE_300_RTK;
        }
        return false;
    }

    /**
     * Verifica se o produto conectado é um Mavic Air 2.
     *
     * @return {@code true} se é Mavic Air 2
     */
    public static boolean isMavicAir2() {
        BaseProduct baseProduct = App.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MAVIC_AIR_2;
        }
        return false;
    }
}
