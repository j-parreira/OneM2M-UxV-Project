/**
 * {@code VideoFeedView} — View de vídeo ao vivo do drone (H.264 decode + display).
 *
 * Recebe o stream de vídeo H.264 do DJI SDK, decodifica e exibe num SurfaceView.
 Suporta deteção de vídeo parado (cover view) após {@value #WAIT_TIME}ms sem frames.
 *
 * @author DJI SDK sample (adaptado por João Parreira)
 * @author Pedro Barbeiro
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.NonNull;

import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.thirdparty.rx.Observable;
import dji.thirdparty.rx.Subscription;
import dji.thirdparty.rx.android.schedulers.AndroidSchedulers;
import dji.thirdparty.rx.functions.Action1;

/**
 * View personalizada para exibir o feed de vídeo ao vivo do drone.
 *
 * <h3>Decodificação de vídeo</h3>
 * <p>Usa o {@link DJICodecManager} da DJI para decodificar o stream H.264.
 * O stream é recebido via {@link VideoFeeder.VideoDataListener} e enviado ao decoder.
 *
 * <h3>Cobertura quando o vídeo para</h3>
 * <p>Se não receber frames durante {@value #WAIT_TIME}ms, exibe o {@code coverView}.
 * Útil para mostrar um placeholder quando o stream de vídeo é interrompido.
 *
 * <h3>Origem</h3>
 * <p>Baseado no sample code da DJI SDK. Adaptado para o Mavic 2 Enterprise Advanced
 * com suporte a stream primário (câmera) e FPV.
 */
public class VideoFeedView extends SurfaceView {

    /** Tag para log. */
    private final static String TAG = "DULFpvWidget";

    /** Gerenciador de decodificação H.264 da DJI. */
    private DJICodecManager codecManager = null;

    /** Listener de dados de vídeo registado no feed. */
    private VideoFeeder.VideoDataListener videoDataListener = null;

    /** Largura do vídeo. */
    private int videoWidth;

    /** Altura do vídeo. */
    private int videoHeight;

    /** Indica se o feed é primário (câmera) ou secundário (FPV). */
    private boolean isPrimaryVideoFeed;

    /** View de cobertura exibida quando o vídeo para. */
    private View coverView;

    /** Tempo em ms sem receber frames antes de mostrar o cover view. */
    private final long WAIT_TIME = 500;

    /** Timestamp do último frame recebido (ms). */
    private final AtomicLong lastReceivedFrameTime = new AtomicLong(0);

    /** Timer observável para verificar se o vídeo parou. */
    private final Observable timer =
            Observable.timer(100, TimeUnit.MICROSECONDS).observeOn(AndroidSchedulers.mainThread()).repeat();

    /** Subscrição do timer (gerenciada para cleanup). */
    private Subscription subscription;

    /** Holder da surface para callbacks de criação/destruição. */
    private SurfaceHolder surfaceHolder;

    //region Lifecycle

    public VideoFeedView(Context context) {
        this(context, null, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    /**
     * Define a view de cobertura (exibida quando o vídeo para).
     *
     * @param view a view de cobertura
     */
    public void setCoverView(View view) {
        coverView = view;
    }

    /**
     * Inicializa a view: surface callbacks e listener de vídeo.
     *
     * @param context o contexto da aplicação
     */
    private void init(Context context) {
        // Evitar exceções no Android Studio Preview
        if (isInEditMode()) {
            return;
        }

        surfaceHolder = getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (codecManager == null) {
                    codecManager = new DJICodecManager(context,
                            holder,
                            getWidth(),
                            getHeight(),
                            isPrimaryVideoFeed
                                    ? UsbAccessoryService.VideoStreamSource.Camera
                                    : UsbAccessoryService.VideoStreamSource.Fpv);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) { }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (codecManager != null) {
                    codecManager.cleanSurface();
                    codecManager.destroyCodec();
                    codecManager = null;
                }
            }
        });

        // Listener para receber frames de vídeo do feed principal
        videoDataListener = (videoBuffer, size) -> {
            Log.d("DEBUG", "(VideoFeedView) Video data received: " + size);
            lastReceivedFrameTime.set(System.currentTimeMillis());

            if (codecManager != null) {
                codecManager.sendDataToDecoder(videoBuffer,
                        size,
                        isPrimaryVideoFeed
                                ? UsbAccessoryService.VideoStreamSource.Camera.getIndex()
                                : UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
            }
        };

        // Timer para detetar se o vídeo parou (mostrar cover view)
        subscription = timer.subscribe(new Action1() {
            @Override
            public void call(Object o) {
                final long now = System.currentTimeMillis();
                final long ellapsedTime = now - lastReceivedFrameTime.get();
                if (coverView != null) {
                    if (ellapsedTime > WAIT_TIME && !ModuleVerificationUtil.isMavic2Product()) {
                        if (coverView.getVisibility() == INVISIBLE) {
                            coverView.setVisibility(VISIBLE);
                        }
                    } else {
                        if (coverView.getVisibility() == VISIBLE) {
                            coverView.setVisibility(INVISIBLE);
                        }
                    }
                }
            }
        });
    }
    //endregion

    /**
     * Regista o listener de vídeo ao vivo.
     *
     * @param videoFeed o feed de vídeo do DJI SDK
     * @param isPrimary {@code true} se é o feed primário (câmera), {@code false} se é FPV
     * @return o listener registado ou null se já registado ou feed null
     */
    public VideoFeeder.VideoDataListener registerLiveVideo(VideoFeeder.VideoFeed videoFeed, boolean isPrimary) {
        isPrimaryVideoFeed = isPrimary;

        if (videoDataListener != null && videoFeed != null && !videoFeed.getListeners().contains(videoDataListener)) {
            videoFeed.addVideoDataListener(videoDataListener);
            return videoDataListener;
        }
        return null;
    }

    /**
     * Reinicia o keyframe do decoder.
     *
     * <p>Útil ao trocar de stream de câmera (RGB → IR) para forçar
     * um keyframe e evitar artefatos visuais.
     */
    public void changeSourceResetKeyFrame() {
        if (codecManager != null) {
            codecManager.resetKeyFrame();
        }
    }

    /**
     * Chamado quando a view é removida da janela.
     *
     * <p>Limpa a subscrição do timer e destrói o gestor de vídeo.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (subscription != null && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
        VideoFeeder.getInstance().destroy();
    }
}
