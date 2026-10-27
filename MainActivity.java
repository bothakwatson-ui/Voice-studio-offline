package com.voicestudio.free;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

/** Completely offline, dependency-free Android voice recording and accompaniment demo. */
public class MainActivity extends Activity {
    private static final int SAMPLE_RATE=16000, MAX_SECONDS=60, MIC_REQUEST=123;
    private static final String[] GENRES={"Gospel", "Afro-pop", "Hip-hop", "Rock"};
    private volatile boolean recording=false;
    private AudioRecord recorder;
    private AudioTrack player;
    private short[] original, enhanced, finished;
    private TextView status;
    private Button recordButton, processButton, previewButton, saveButton;
    private Spinner genreSpinner;
    private SeekBar backingVolume;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(28,30,28,28); column.setBackgroundColor(Color.rgb(246,248,252));
        ScrollView scroll=new ScrollView(this); scroll.addView(column); setContentView(scroll);
        TextView title=text("🎙 Voice Studio — Offline",27,true); column.addView(title);
        column.addView(text("Record your voice, clean it, add locally synthesized backing music, and save a WAV song. No internet or account required.",15,false));
        column.addView(text("STEP 1 · RECORD",20,true));
        recordButton=button("● Start recording",column,()->toggleRecording());
        column.addView(text("Recording is limited to 60 seconds. Use a quiet room for the best noise reduction.",13,false));
        column.addView(text("STEP 2 · PROCESS",20,true));
        genreSpinner=new Spinner(this);
        genreSpinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,GENRES));
        column.addView(genreSpinner);
        column.addView(text("Backing track volume",15,false));
        backingVolume=new SeekBar(this); backingVolume.setMax(100); backingVolume.setProgress(35); column.addView(backingVolume);
        processButton=button("Enhance voice + make backing track",column,()->process());
        column.addView(text("STEP 3 · LISTEN & SAVE",20,true));
        previewButton=button("▶ Play mixed song",column,()->preview());
        saveButton=button("↓ Save WAV to Music",column,()->save());
        status=text("Ready. Press Start recording and allow microphone access.",15,false);
        status.setPadding(0,22,0,15); column.addView(status);
        processButton.setEnabled(false); previewButton.setEnabled(false); saveButton.setEnabled(false);
    }
    private TextView text(String content,int size,boolean bold){
        TextView t=new TextView(this); t.setText(content); t.setTextColor(Color.rgb(26,36,56)); t.setTextSize(size);
        t.setPadding(0,15,0,12); if(bold)t.setTypeface(null,1); return t;
    }
    private Button button(String label,LinearLayout target,Runnable click){
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); target.addView(b); b.setOnClickListener(v->click.run()); return b;
    }
    private void message(String s){runOnUiThread(()->status.setText(s));}
    private void toggleRecording(){
        if(recording){stopRecording();return;}
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);return;
        }
        startRecording();
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] result){
        super.onRequestPermissionsResult(request,perms,result);
        if(request==MIC_REQUEST){if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED)startRecording();else message("Microphone permission is required to record.");}
    }
    private void startRecording(){
        try{
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new Exception("No compatible microphone format");
            recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,4096));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new Exception("Microphone initialization failed");
            recorder.startRecording(); recording=true; original=null;enhanced=null;finished=null;
            recordButton.setText("■ Stop recording");processButton.setEnabled(false);previewButton.setEnabled(false);saveButton.setEnabled(false);
            message("Recording… tap Stop when finished (maximum 60 seconds).");
            new Thread(()->{
                short[] buffer=new short[2048]; short[] data=new short[SAMPLE_RATE*MAX_SECONDS];int n=0;
                try{
                    while(recording && n<data.length){
                        int read=recorder.read(buffer,0,Math.min(buffer.length,data.length-n));
                        if(read>0){System.arraycopy(buffer,0,data,n,read);n+=read;}
                        else if(read<0)break;
                    }
                }catch(Exception e){message("Recording issue: "+e.getMessage());}
                finally{
                    recording=false;try{recorder.stop();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}
                    recorder=null;original=Arrays.copyOf(data,n);
                    final int seconds=n/SAMPLE_RATE;
                    runOnUiThread(()->{
                        recordButton.setText("● Start new recording");processButton.setEnabled(original.length>1000);
                        status.setText(original.length>1000?"Recorded about "+seconds+" seconds. Choose a genre and process.":"Recording was too short. Try again.");
                    });
                }
            }).start();
        }catch(Exception ex){message("Cannot record: "+ex.getMessage());recording=false;}
    }
    private void stopRecording(){recording=false;recordButton.setEnabled(false);recordButton.postDelayed(()->recordButton.setEnabled(true),500);}
    private void process(){
        if(original==null || original.length<1000)return;
        processButton.setEnabled(false);previewButton.setEnabled(false);saveButton.setEnabled(false);
        final int genre=genreSpinner.getSelectedItemPosition();final float volume=backingVolume.getProgress()/100f;
        message("Enhancing vocals and synthesizing instrumentals offline…");
        new Thread(()->{
            try{
                enhanced=enhance(original);
                finished=mix(enhanced,genre,volume);
                runOnUiThread(()->{status.setText("Finished! Listen or save your song as a WAV file.");previewButton.setEnabled(true);saveButton.setEnabled(true);});
            }catch(Exception ex){message("Processing failed: "+ex.getMessage());}
            finally{runOnUiThread(()->processButton.setEnabled(true));}
        }).start();
    }
    /** High-pass filter, gentle amplitude gate and peak normalization. This is NOT neural denoising. */
    private short[] enhance(short[] input){
        int n=input.length; float[] signal=new float[n];
        float xPrev=0,yPrev=0,peak=0;
        for(int i=0;i<n;i++){
            float x=input[i]/32768f;float y=0.985f*(yPrev+x-xPrev);xPrev=x;yPrev=y;signal[i]=y;
        }
        // RMS envelope with 15 ms attack/release; gate softly when very quiet.
        float smooth=0;for(int i=0;i<n;i++){
            float abs=Math.abs(signal[i]);smooth=0.996f*smooth+0.004f*abs;
            float gain=smooth<0.010f?0.25f+0.75f*(smooth/0.010f):1f;
            signal[i]*=gain;peak=Math.max(peak,Math.abs(signal[i]));
        }
        float scale=peak>0.001f?Math.min(5f,0.85f/peak):1f;
        short[] out=new short[n];for(int i=0;i<n;i++)out[i]=clamp(signal[i]*scale);
        return out;
    }
    /** Generate an original procedural loop with chord tones, bass and percussion. */
    private short[] mix(short[] vocal,int genre,float backingGain){
        int n=vocal.length;short[] result=new short[n];
        double bpm=new double[]{78,106,90,118}[Math.max(0,Math.min(3,genre))];
        double beatSec=60.0/bpm;double barSec=4*beatSec;
        int[][] chordRoots={{48,53,55,48},{48,46,53,55},{48,44,41,43},{48,53,55,46}};
        Random random=new Random(9137);
        float[] hatNoise=new float[4096];for(int i=0;i<hatNoise.length;i++)hatNoise[i]=(random.nextFloat()*2-1);
        for(int i=0;i<n;i++){
            double t=(double)i/SAMPLE_RATE;int bar=(int)(t/barSec);
            int root=chordRoots[genre][bar%4];double within=t%barSec;
            // Warm chord pad with smooth repeated quarter-note pulses.
            double pulse=0.5+0.5*Math.cos(2*Math.PI*(within/beatSec));
            double pad=0;for(int semitone:new int[]{0,4,7}){
                double freq=440*Math.pow(2,(root+12+semitone-69)/12.0);
                pad+=Math.sin(2*Math.PI*freq*t)*0.10;
            }
            pad*=0.3+0.7*pulse;
            double bassFreq=440*Math.pow(2,(root-12-69)/12.0);
            double bass=0.20*Math.sin(2*Math.PI*bassFreq*t)*(0.65+0.35*Math.cos(Math.PI*within/beatSec));
            // Kick on beats 1 and 3, snare on beats 2 and 4, soft eighth-note hats.
            double onBeat=within%beatSec;int beat=(int)(within/beatSec);
            double kick=(beat%2==0 && onBeat<0.16)?0.38*Math.sin(2*Math.PI*(65-190*onBeat)*onBeat)*Math.exp(-25*onBeat):0;
            double snare=(beat%2==1 && onBeat<0.11)?hatNoise[i%hatNoise.length]*0.18*Math.exp(-25*onBeat):0;
            double hatPosition=within%(beatSec/2.0);
            double hat=hatPosition<0.035?hatNoise[(i*7)%hatNoise.length]*0.075*Math.exp(-100*hatPosition):0;
            double bg=(pad+bass+kick+snare+hat)*backingGain;
            result[i]=clamp((float)(vocal[i]/32768f*0.78+bg));
        }
        return result;
    }
    private short clamp(float v){return (short)(Math.max(-1.0f,Math.min(0.9999f,v))*32767);}
    private void preview(){
        if(finished==null)return;
        previewButton.setEnabled(false);message("Playing your finished song…");
        new Thread(()->{
            try{
                if(player!=null){player.stop();player.release();player=null;}
                int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                player=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(min,8192)).setTransferMode(AudioTrack.MODE_STREAM).build();
                player.play();int pos=0;
                while(pos<finished.length){int w=player.write(finished,pos,Math.min(4096,finished.length-pos));if(w<=0)break;pos+=w;}
                player.stop();player.release();player=null;
                message("Playback complete.");
            }catch(Exception ex){message("Playback issue: "+ex.getMessage());}
            finally{runOnUiThread(()->previewButton.setEnabled(true));}
        }).start();
    }
    /** Write a standard mono PCM16 WAV into Music/VoiceStudio via MediaStore (Android 10+). */
    private void save(){
        if(finished==null)return;
        if(Build.VERSION.SDK_INT<29){message("Saving currently requires Android 10 or newer.");return;}
        saveButton.setEnabled(false);message("Saving WAV file to your Music folder…");
        new Thread(()->{
            Uri uri=null;
            try{
                String name="VoiceStudio_"+System.currentTimeMillis()+".wav";
                ContentValues values=new ContentValues();values.put(MediaStore.Audio.Media.DISPLAY_NAME,name);
                values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/VoiceStudio");
                values.put(MediaStore.Audio.Media.IS_PENDING,1);
                uri=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new Exception("Media storage unavailable");
                try(OutputStream output=getContentResolver().openOutputStream(uri)){
                    if(output==null)throw new Exception("Unable to open output");
                    writeWav(output,finished);
                }
                values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);getContentResolver().update(uri,values,null,null);
                message("Saved successfully: Music/VoiceStudio/"+name);
            }catch(Exception ex){if(uri!=null)getContentResolver().delete(uri,null,null);message("Save failed: "+ex.getMessage());}
            finally{runOnUiThread(()->saveButton.setEnabled(true));}
        }).start();
    }
    private void putInt(OutputStream o,int v)throws Exception{for(int i=0;i<4;i++)o.write((v>>(8*i))&255);}
    private void putShort(OutputStream o,int v)throws Exception{o.write(v&255);o.write((v>>8)&255);}
    private void writeWav(OutputStream o,short[] samples)throws Exception{
        int bytes=samples.length*2;o.write(new byte[]{'R','I','F','F'});putInt(o,36+bytes);
        o.write(new byte[]{'W','A','V','E','f','m','t',' '});putInt(o,16);putShort(o,1);putShort(o,1);
        putInt(o,SAMPLE_RATE);putInt(o,SAMPLE_RATE*2);putShort(o,2);putShort(o,16);
        o.write(new byte[]{'d','a','t','a'});putInt(o,bytes);
        byte[] buf=new byte[8192];int start=0;
        while(start<samples.length){int count=Math.min(buf.length/2,samples.length-start);
            for(int i=0;i<count;i++){short s=samples[start+i];buf[2*i]=(byte)s;buf[2*i+1]=(byte)(s>>8);}
            o.write(buf,0,count*2);start+=count;
        }
    }
    @Override protected void onDestroy(){recording=false;if(player!=null){try{player.pause();player.flush();player.release();}catch(Exception ignored){}}super.onDestroy();}
}
