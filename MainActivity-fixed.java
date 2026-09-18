package com.elrixai.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API_URL = "https://alrix.onrender.com/chat";
    private static final String PREFS = "elrix_preferences";
    private static final String HISTORY = "history";
    private static final String SESSIONS = "sessions";
    private static final String AUTO_READ = "auto_read";
    private static final String PIPER_VOICE_URL = "https://raw.githubusercontent.com/rodrigopietro039-ui/Alrix/main/elrix-piper-voice.zip";
    private static final String PIPER_VOICE_DIR = "vits-piper-pt_BR-faber-medium";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout messagesLayout;
    private EditText input;
    private TextView modeLabel;
    private TextView typingIndicator;
    private ScrollView conversationScroll;
    private AlphaAnimation typingAnimation;
    private TextToSpeech textToSpeech;
    private boolean voiceReady = false;
    // Offline neural voice: Piper pt_BR-faber-medium through Sherpa-ONNX.
    private OfflineTts piperTts;
    private volatile boolean piperReady = false;
    private volatile boolean piperInitFinished = false;
    private volatile boolean piperDownloading = false;
    private volatile String piperError = null;
    private TextView voiceStatus;
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private volatile AudioTrack piperTrack;
    private volatile int speechGeneration = 0;
    private boolean autoRead = false;
    private String pendingSpeech = null;
    private Button autoReadButton;
    private JSONArray conversation = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private String mode = "criativa";
    private boolean sending = false;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale ptBr = new Locale("pt", "BR");
                textToSpeech.setLanguage(ptBr);
                Voice chosen = null;
                try {
                    Set<Voice> voices = textToSpeech.getVoices();
                    for (Voice voice : voices) {
                        Locale locale = voice.getLocale();
                        String name = voice.getName().toLowerCase(Locale.ROOT);
                        boolean brazilianPortuguese = "pt".equals(locale.getLanguage()) && ("BR".equalsIgnoreCase(locale.getCountry()) || locale.getCountry().isEmpty());
                        boolean male = name.contains("male") && !name.contains("female");
                        if (brazilianPortuguese && male && (chosen == null || voice.isNetworkConnectionRequired())) chosen = voice;
                    }
                } catch (Exception ignored) { }
                if (chosen != null) textToSpeech.setVoice(chosen);
                voiceReady = textToSpeech.getLanguage() != null;
                textToSpeech.setSpeechRate(0.92f);
                textToSpeech.setPitch(0.82f);
                if (autoRead && pendingSpeech != null) {
                    String queued = pendingSpeech;
                    pendingSpeech = null;
                    speakMessage(queued);
                }
            }
        });
        initPiperTts();
        getWindow().setStatusBarColor(Color.rgb(8, 11, 18));
        getWindow().setNavigationBarColor(Color.rgb(8, 11, 18));
        autoRead = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_READ, false);
        buildInterface();
        if (autoRead && autoReadButton != null) {
            autoReadButton.setText("🔊");
            autoReadButton.setBackground(round(Color.rgb(24, 127, 220), dp(14)));
            autoReadButton.setContentDescription("Desativar leitura automática");
        }
        loadConversation();
    }

    /** Downloads the optional Piper voice once, then uses it from app storage. */
    private void initPiperTts() {
        new Thread(() -> {
            try {
                File voiceRoot = ensurePiperVoice();
                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(new File(voiceRoot, "pt_BR-faber-medium.onnx").getAbsolutePath());
                vits.setTokens(new File(voiceRoot, "tokens.txt").getAbsolutePath());
                vits.setDataDir(new File(voiceRoot, "espeak-ng-data").getAbsolutePath());
                vits.setNoiseScale(0.667f);
                vits.setNoiseScaleW(0.8f);
                vits.setLengthScale(1.0f);

                OfflineTtsModelConfig model = new OfflineTtsModelConfig();
                model.setVits(vits);
                model.setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2)));
                model.setDebug(false);
                model.setProvider("cpu");

                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(model);
                config.setMaxNumSentences(1);
                piperTts = new OfflineTts(null, config);
                piperError = null;
                piperReady = true;
            } catch (Throwable error) {
                piperReady = false;
                piperError = describePiperError(error);
                Log.e("ElrixPiper", "Piper initialization failed: " + piperError, error);
            } finally {
                piperDownloading = false;
                piperInitFinished = true;
                mainHandler.post(this::updateVoiceStatus);
            }
        }, "elrix-piper-init").start();
    }

    private File ensurePiperVoice() throws Exception {
        File root = new File(getFilesDir(), PIPER_VOICE_DIR);
        if (isPiperVoiceComplete(root)) return root;
        piperDownloading = true;
        mainHandler.post(this::updateVoiceStatus);
        File zipFile = new File(getFilesDir(), "elrix-piper-voice.zip.part");
        HttpURLConnection connection = (HttpURLConnection) new URL(PIPER_VOICE_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("download da voz falhou: HTTP " + connection.getResponseCode());
        }
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(zipFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } finally {
            connection.disconnect();
        }
        if (zipFile.length() < 1024 * 1024) throw new IllegalStateException("arquivo da voz incompleto");
        if (root.exists()) deleteRecursively(root);
        File parent = root.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                String prefix = PIPER_VOICE_DIR + "/";
                if (!name.startsWith(prefix) || name.contains("../")) throw new SecurityException("arquivo de voz inválido");
                File target = new File(getFilesDir(), name);
                if (entry.isDirectory()) target.mkdirs();
                else {
                    File targetParent = target.getParentFile();
                    if (targetParent != null) targetParent.mkdirs();
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
        if (!isPiperVoiceComplete(root)) throw new IllegalStateException("voz neural extraída incompleta");
        zipFile.delete();
        return root;
    }

    private boolean isPiperVoiceComplete(File root) {
        File model = new File(root, "pt_BR-faber-medium.onnx");
        File tokens = new File(root, "tokens.txt");
        File espeak = new File(root, "espeak-ng-data");
        return model.isFile() && model.length() > 1024 && tokens.isFile() && tokens.length() > 10 && espeak.isDirectory() && espeak.list() != null && espeak.list().length > 0;
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private String describePiperError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private void updateVoiceStatus() {
        if (voiceStatus == null) return;
        if (piperDownloading) {
            voiceStatus.setText("Baixando voz neural local…");
            voiceStatus.setTextColor(Color.rgb(106, 132, 153));
        } else if (piperReady) {            voiceStatus.setText("Voz neural local Piper ativa");
            voiceStatus.setTextColor(Color.rgb(100, 210, 150));
        } else if (piperInitFinished) {
            voiceStatus.setText("Voz neural local indisponível · usando voz do aparelho");
            voiceStatus.setTextColor(Color.rgb(245, 180, 100));
        } else {
            voiceStatus.setText("Carregando voz neural local…");
            voiceStatus.setTextColor(Color.rgb(106, 132, 153));
        }
    }

    private void showPiperDiagnostics() {
        if (!piperInitFinished || piperReady) return;
        String detail = piperError == null ? "motivo não informado" : piperError;
        new AlertDialog.Builder(this)
                .setTitle("Voz neural local")
                .setMessage("A Elrix continua funcionando com a voz do aparelho. A voz Piper não iniciou nesta instalação.\n\nDetalhe: " + detail)
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 11, 18));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(76)));

        Button history = button("☷", Color.rgb(15, 23, 38), Color.rgb(115, 190, 255));
        history.setTextSize(22);
        history.setContentDescription("Conversas passadas");
        history.setOnClickListener(v -> showHistory());
        header.addView(history, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView mark = textView("E", 23, Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, 1);
        mark.setBackground(round(Color.rgb(24, 127, 220), dp(15)));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        markParams.leftMargin = dp(10);
        header.addView(mark, markParams);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12), 0, 0, 0);
        TextView title = textView("Elrix Aí", 20, Color.WHITE);
        title.setTypeface(null, 1);
        modeLabel = textView("Criativa · sua parceira de criatividade", 12, Color.rgb(146, 169, 190));
        titleBox.addView(title);
        titleBox.addView(modeLabel);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        Button clear = button("＋", Color.rgb(15, 23, 38), Color.rgb(115, 190, 255));
        clear.setTextSize(25);
        clear.setContentDescription("Nova conversa");
        clear.setOnClickListener(v -> clearConversation());
        header.addView(clear, new LinearLayout.LayoutParams(dp(42), dp(42)));

        autoReadButton = button("🔈", Color.rgb(15, 23, 38), Color.rgb(115, 190, 255));
        autoReadButton.setTextSize(18);
        autoReadButton.setContentDescription("Ativar leitura automática");
        autoReadButton.setOnClickListener(v -> toggleAutoRead());
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(dp(48), dp(42));
        autoParams.leftMargin = dp(6);
        header.addView(autoReadButton, autoParams);

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setPadding(dp(14), dp(8), dp(14), dp(8));
        modeBar.setGravity(Gravity.CENTER_VERTICAL);
        String[] modes = {"criativa", "tarefas", "estudos"};
        String[] labels = {"✦ Criativa", "✓ Tarefas", "◈ Estudos"};
        for (int i = 0; i < modes.length; i++) {
            final String selectedMode = modes[i];
            Button modeButton = button(labels[i], Color.rgb(12, 20, 32), Color.rgb(150, 170, 185));
            modeButton.setTextSize(12);
            modeButton.setAllCaps(false);
            modeButton.setOnClickListener(v -> setMode(selectedMode));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(36));
            if (i > 0) params.leftMargin = dp(8);
            modeBar.addView(modeButton, params);
        }
        modeScroll.addView(modeBar);
        root.addView(modeScroll, new LinearLayout.LayoutParams(-1, dp(54)));

        conversationScroll = new ScrollView(this);
        messagesLayout = new LinearLayout(this);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(dp(18), dp(16), dp(18), dp(10));
        conversationScroll.addView(messagesLayout);
        root.addView(conversationScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        typingIndicator = textView("Elrix está pensando  •  •  •", 13, Color.rgb(115, 190, 255));
        typingIndicator.setGravity(Gravity.CENTER);
        typingIndicator.setVisibility(View.GONE);
        root.addView(typingIndicator, new LinearLayout.LayoutParams(-1, dp(30)));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(14), dp(6), dp(14), dp(6));
        input = new EditText(this);
        input.setHint("Converse com a Elrix...");
        input.setHintTextColor(Color.rgb(113, 143, 164));
        input.setTextColor(Color.WHITE);
        input.setTextSize(16);
        input.setGravity(Gravity.TOP);
        input.setPadding(dp(12), dp(10), dp(8), dp(10));
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setBackground(round(Color.rgb(15, 23, 38), dp(20)));
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button send = button("↑", Color.rgb(24, 127, 220), Color.WHITE);
        send.setTextSize(25);
        send.setOnClickListener(v -> sendMessage());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.leftMargin = dp(8);
        composer.addView(send, sendParams);
        root.addView(composer, new LinearLayout.LayoutParams(-1, dp(72)));

        voiceStatus = textView("Carregando voz neural local…", 10, Color.rgb(106, 132, 153));
        voiceStatus.setGravity(Gravity.CENTER);
        voiceStatus.setContentDescription("Status da voz neural local; toque para ver detalhes se houver falha");
        voiceStatus.setOnClickListener(v -> showPiperDiagnostics());
        root.addView(voiceStatus, new LinearLayout.LayoutParams(-1, dp(20)));
        TextView footer = textView("IA real · conversas salvas neste aparelho", 10, Color.rgb(106, 132, 153));
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(25)));
        setContentView(root);
        updateVoiceStatus();
    }

    private TextView textView(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value, int background, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(color);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(round(background, dp(14)));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void toggleAutoRead() {
        autoRead = !autoRead;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_READ, autoRead).apply();
        if (autoReadButton != null) {
            autoReadButton.setText(autoRead ? "🔊" : "🔈");
            autoReadButton.setBackground(round(autoRead ? Color.rgb(24, 127, 220) : Color.rgb(15, 23, 38), dp(14)));
            autoReadButton.setContentDescription(autoRead ? "Desativar leitura automática" : "Ativar leitura automática");
        }
        Toast.makeText(this, autoRead ? "Leitura automática ativada" : "Leitura automática desativada", Toast.LENGTH_SHORT).show();
    }

    private void setMode(String selectedMode) {
        mode = selectedMode;
        String label = selectedMode.substring(0, 1).toUpperCase() + selectedMode.substring(1);
        modeLabel.setText(label + " · sua parceira de criatividade");
    }

    private void loadConversation() {
        try {
            SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String savedSessions = preferences.getString(SESSIONS, "[]");
            sessions = new JSONArray(savedSessions);
            String saved = preferences.getString(HISTORY, "");
            if (saved.isEmpty()) {
                addMessage("assistant", "Olá!! Como vai? Para que você precisará de mim hoje?!");
            } else {
                conversation = new JSONArray(saved);
                renderConversation();
            }
        } catch (Exception ignored) {
            conversation = new JSONArray();
            addMessage("assistant", "Olá!! Como vai? Para que você precisará de mim hoje?!");
        }
    }

    private void saveConversation() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(HISTORY, conversation.toString())
                .putString(SESSIONS, sessions.toString())
                .apply();
    }

    private void clearConversation() {
        archiveCurrentConversation();
        conversation = new JSONArray();
        input.setText("");
        renderConversation();
        addMessage("assistant", "Olá!! Como vai? Para que você precisará de mim hoje?!");
    }

    private void archiveCurrentConversation() {
        try {
            boolean hasUserMessage = false;
            for (int i = 0; i < conversation.length(); i++) {
                if ("user".equals(conversation.getJSONObject(i).optString("role"))) {
                    hasUserMessage = true;
                    break;
                }
            }
            if (!hasUserMessage) return;
            JSONObject archived = new JSONObject();
            archived.put("id", String.valueOf(System.currentTimeMillis()));
            archived.put("title", conversationTitle(conversation));
            archived.put("messages", new JSONArray(conversation.toString()));
            sessions.put(archived);
            while (sessions.length() > 30) sessions.remove(0);
            saveConversation();
        } catch (Exception ignored) { }
    }

    private String conversationTitle(JSONArray items) {
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("user".equals(item.optString("role"))) {
                    String title = item.optString("content", "Conversa").replace('\n', ' ').trim();
                    if (title.length() > 30) title = title.substring(0, 30) + "…";
                    return title;
                }
            }
        } catch (Exception ignored) { }
        return "Conversa sem título";
    }

    private void showHistory() {
        try {
            if (sessions.length() == 0) {
                new AlertDialog.Builder(this)
                        .setTitle("Conversas passadas")
                        .setMessage("Ainda não há conversas arquivadas. Toque em ＋ para começar uma nova conversa.")
                        .setPositiveButton("Fechar", null)
                        .show();
                return;
            }
            String[] titles = new String[sessions.length()];
            for (int i = 0; i < sessions.length(); i++) {
                titles[i] = sessions.getJSONObject(i).optString("title", "Conversa " + (i + 1));
            }
            new AlertDialog.Builder(this)
                    .setTitle("Conversas passadas")
                    .setItems(titles, (dialog, which) -> loadArchivedConversation(which))
                    .setNegativeButton("Fechar", null)
                    .setNeutralButton("Apagar todas", (dialog, which) -> deleteAllSessions())
                    .show();
        } catch (Exception ignored) { }
    }

    private void loadArchivedConversation(int index) {
        try {
            JSONObject archived = sessions.getJSONObject(index);
            conversation = new JSONArray(archived.getJSONArray("messages").toString());
            saveConversation();
            renderConversation();
        } catch (Exception ignored) { }
    }

    private void deleteAllSessions() {
        sessions = new JSONArray();
        saveConversation();
    }

    private void renderConversation() {
        messagesLayout.removeAllViews();
        try {
            for (int i = 0; i < conversation.length(); i++) {
                JSONObject item = conversation.getJSONObject(i);
                addBubble(item.optString("role"), item.optString("content"), i);
            }
        } catch (Exception ignored) { }
        scrollToBottom();
    }

    private void addMessage(String role, String content) {
        try {
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", content);
            conversation.put(item);
            saveConversation();
            addBubble(role, content, conversation.length() - 1);
            scrollToBottom();
        } catch (Exception ignored) { }
    }

    private void addBubble(String role, String content, int index) {
        boolean user = "user".equals(role);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, 0, 0, dp(14));

        if (!user) {
            TextView avatar = textView("E", 15, Color.WHITE);
            avatar.setGravity(Gravity.CENTER);
            avatar.setTypeface(null, 1);
            avatar.setBackground(round(Color.rgb(24, 127, 220), dp(11)));
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(30), dp(30));
            avatarParams.rightMargin = dp(8);
            row.addView(avatar, avatarParams);
        }

        LinearLayout bubbleBox = new LinearLayout(this);
        bubbleBox.setOrientation(LinearLayout.VERTICAL);
        bubbleBox.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        TextView bubble = textView(content, 16, Color.rgb(235, 246, 255));
        bubble.setPadding(dp(15), dp(12), dp(15), dp(12));
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setBackground(round(user ? Color.rgb(12, 99, 190) : Color.rgb(15, 23, 38), dp(18)));
        bubbleBox.addView(bubble, new LinearLayout.LayoutParams(dp(310), -2));

        // Ações ficam em um quadrinho ao pressionar a mensagem; copiar/regenerar ficam acessíveis abaixo.
        bubble.setOnLongClickListener(v -> {
            showMessageActions(index, user);
            return true;
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        Button copy = button("Copiar", Color.rgb(19, 48, 79), Color.rgb(150, 215, 255));
        copy.setTextSize(11);
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> copyMessage(content));
        actions.addView(copy, new LinearLayout.LayoutParams(dp(76), dp(30)));
        if (!user && index > 0) {
            Button regenerate = button("Regenerar", Color.rgb(19, 48, 79), Color.rgb(150, 215, 255));
            regenerate.setTextSize(11);
            regenerate.setAllCaps(false);
            LinearLayout.LayoutParams regenParams = new LinearLayout.LayoutParams(dp(92), dp(30));
            regenParams.leftMargin = dp(6);
            actions.addView(regenerate, regenParams);
            regenerate.setOnClickListener(v -> regenerateMessage(index));

            Button listen = button("Ouvir", Color.rgb(19, 48, 79), Color.rgb(150, 215, 255));
            listen.setTextSize(11);
            listen.setAllCaps(false);
            listen.setContentDescription("Ouvir resposta da Elrix");
            LinearLayout.LayoutParams listenParams = new LinearLayout.LayoutParams(dp(70), dp(30));
            listenParams.leftMargin = dp(6);
            actions.addView(listen, listenParams);
            listen.setOnClickListener(v -> speakMessage(content));
        }
        bubbleBox.addView(actions);
        row.addView(bubbleBox, new LinearLayout.LayoutParams(dp(310), -2));
        messagesLayout.addView(row);
    }

    private void showMessageActions(int index, boolean user) {
        String[] actions = user ? new String[]{"Editar", "Excluir", "Copiar"} : (index > 0 ? new String[]{"Regenerar", "Copiar", "Ouvir"} : new String[]{"Copiar", "Ouvir"});
        new AlertDialog.Builder(this)
                .setTitle("Ações da mensagem")
                .setItems(actions, (dialog, which) -> {
                    if (user) {
                        if (which == 0) editMessage(index);
                        else if (which == 1) deleteFromMessage(index);
                        else copyMessageAt(index);
                    } else if (index > 0 && which == 0) regenerateMessage(index);
                    else if ((index > 0 && which == 2) || (index == 0 && which == 1)) speakMessageAt(index);
                    else copyMessageAt(index);
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void copyMessageAt(int index) {
        try { copyMessage(conversation.getJSONObject(index).optString("content", "")); }
        catch (Exception ignored) { }
    }

    private void copyMessage(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Mensagem da Elrix", content));
        Toast.makeText(this, "Mensagem copiada", Toast.LENGTH_SHORT).show();
    }

    private void speakMessageAt(int index) {
        try { speakMessage(conversation.getJSONObject(index).optString("content", "")); }
        catch (Exception ignored) { }
    }

    private void speakMessage(String content) {
        if (content == null || content.trim().isEmpty()) return;
        final int generation = ++speechGeneration;
        stopPiperPlayback();
        if (piperReady && piperTts != null) {
            final String text = content.trim();
            speechExecutor.execute(() -> speakWithPiper(text, generation));
            return;
        }
        // Fallback while the neural model is loading or if this device cannot load it.
        if (!voiceReady || textToSpeech == null) {
            pendingSpeech = content;
            Toast.makeText(this, "A voz ainda está carregando", Toast.LENGTH_SHORT).show();
            return;
        }
        textToSpeech.stop();
        String remaining = content.trim();
        boolean first = true;
        while (!remaining.isEmpty()) {
            int end = Math.min(remaining.length(), 3500);
            if (end < remaining.length()) {
                int boundary = remaining.lastIndexOf(' ', end);
                if (boundary > 500) end = boundary;
            }
            String chunk = remaining.substring(0, end).trim();
            textToSpeech.speak(chunk, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "elrix-response-" + end);
            first = false;
            remaining = remaining.substring(end).trim();
        }
    }

    private void speakWithPiper(String content, int generation) {
        AudioTrack track = null;
        try {
            String remaining = content;
            while (!remaining.isEmpty() && generation == speechGeneration) {
                int end = Math.min(remaining.length(), 900);
                if (end < remaining.length()) {
                    int boundary = remaining.lastIndexOf(' ', end);
                    if (boundary > 200) end = boundary;
                }
                String chunk = remaining.substring(0, end).trim();
                GeneratedAudio audio = piperTts.generate(chunk, 0, 0.92f);
                if (track == null) {
                    // PCM16 is better supported than PCM_FLOAT by older Samsung
                    // AudioTrack implementations and uses less playback memory.
                    int minBuffer = AudioTrack.getMinBufferSize(
                            audio.getSampleRate(), AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuffer <= 0) minBuffer = 8192;
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    AudioFormat format = new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(audio.getSampleRate())
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build();
                    track = new AudioTrack(attributes, format, Math.max(minBuffer, 8192),
                            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        track.release();
                        throw new IllegalStateException("AudioTrack não inicializou");
                    }
                    piperTrack = track;
                    track.play();
                }
                float[] samples = audio.getSamples();
                short[] pcm = new short[samples.length];
                for (int i = 0; i < samples.length; i++) {
                    float value = Math.max(-1.0f, Math.min(1.0f, samples[i]));
                    pcm[i] = (short) (value * (value < 0 ? 32768 : 32767));
                }
                int offset = 0;
                while (offset < pcm.length && generation == speechGeneration) {
                    int written = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) break;
                    offset += written;
                }
                remaining = remaining.substring(end).trim();
            }
        } catch (Throwable error) {
            // Do not crash the conversation if a low-memory device rejects neural playback.
            piperReady = false;
            piperError = describePiperError(error);
            Log.e("ElrixPiper", "Piper playback failed: " + piperError, error);
            mainHandler.post(this::updateVoiceStatus);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
                if (piperTrack == track) piperTrack = null;
            }
        }
    }

    private void stopPiperPlayback() {
        AudioTrack track = piperTrack;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) { }
        }
    }

    private void regenerateMessage(int index) {
        if (sending || index < 0 || index >= conversation.length()) return;
        conversation = messagesBefore(index);
        saveConversation();
        renderConversation();
        requestReply();
    }

    private void editMessage(int index) {
        try {
            String original = conversation.getJSONObject(index).optString("content", "");
            EditText editor = new EditText(this);
            editor.setText(original);
            editor.setTextColor(Color.WHITE);
            editor.setHintTextColor(Color.GRAY);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editor.setPadding(dp(12), dp(8), dp(12), dp(8));
            editor.setBackground(round(Color.rgb(15, 23, 38), dp(12)));
            new AlertDialog.Builder(this)
                    .setTitle("Editar sua mensagem")
                    .setView(editor)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Continuar conversa", (dialog, which) -> {
                        String edited = editor.getText().toString().trim();
                        if (!edited.isEmpty()) editAndResend(index, edited);
                    })
                    .show();
        } catch (Exception ignored) { }
    }

    private void editAndResend(int index, String edited) {
        if (sending) return;
        // Primeiro substitui visualmente a conversa pela versão anterior à mensagem editada.
        conversation = messagesBefore(index);
        saveConversation();
        renderConversation();
        addMessage("user", edited);
        requestReply();
    }

    private void deleteFromMessage(int index) {
        if (sending) return;
        new AlertDialog.Builder(this)
                .setTitle("Excluir mensagem?")
                .setMessage("A conversa será refeita sem esta mensagem e sem as respostas seguintes.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (dialog, which) -> {
                    conversation = messagesBefore(index);
                    if (conversation.length() == 0) addMessage("assistant", "Olá!! Como vai? Para que você precisará de mim hoje?!");
                    else {
                        saveConversation();
                        renderConversation();
                    }
                })
                .show();
    }

    private JSONArray messagesBefore(int index) {
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < index && i < conversation.length(); i++) result.put(conversation.get(i));
        } catch (Exception ignored) { }
        return result;
    }

    private void sendMessage() {
        if (sending) return;
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return;
        input.setText("");
        addMessage("user", value);
        requestReply();
    }

    private void requestReply() {
        if (sending) return;
        sending = true;
        setLoading(true);
        new Thread(() -> {
            try {
                String reply = callBackend(mode);
                mainHandler.post(() -> {
                    addMessage("assistant", reply);
                    if (autoRead) speakMessage(reply);
                    sending = false;
                    setLoading(false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    addMessage("assistant", "Tive um problema de conexão agora. Tente novamente em alguns instantes.");
                    sending = false;
                    setLoading(false);
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        typingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            typingAnimation = new AlphaAnimation(0.35f, 1.0f);
            typingAnimation.setDuration(650);
            typingAnimation.setRepeatMode(Animation.REVERSE);
            typingAnimation.setRepeatCount(Animation.INFINITE);
            typingIndicator.startAnimation(typingAnimation);
        } else {
            typingIndicator.clearAnimation();
        }
        scrollToBottom();
    }

    private void scrollToBottom() {
        if (conversationScroll != null) conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String callBackend(String currentMode) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject payload = new JSONObject();
        payload.put("mode", currentMode);
        JSONArray recent = new JSONArray();
        int start = Math.max(0, conversation.length() - 20);
        for (int i = start; i < conversation.length(); i++) recent.put(conversation.get(i));
        payload.put("messages", recent);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload.toString().getBytes("UTF-8"));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        JSONObject response = new JSONObject(result.toString());
        if (status >= 400) throw new Exception(response.optString("error"));
        return response.optString("reply", "Fiquei sem palavras por um instante. Pode tentar de novo?");
    }

    @Override
    protected void onDestroy() {
        ++speechGeneration;
        stopPiperPlayback();
        speechExecutor.shutdownNow();
        if (piperTts != null) {
            try { piperTts.release(); } catch (Exception ignored) { }
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
