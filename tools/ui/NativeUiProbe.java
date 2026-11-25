package com.neotalkie.device;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Host-only adapter around the production Java UI helpers. No Android/ADB/services. */
public final class NativeUiProbe {
  static String decode(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
  static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
    for(String line;(line=reader.readLine())!=null;) {
      String[] a=line.split("\t",-1);
      if(a[0].equals("text")) {
        String label=decode(a[1]),body=decode(a[2]);
        final int width=Integer.parseInt(a[6]);
        TurnText.Measure measure=new TurnText.Measure(){public int fit(String s){int used=0,end=0;while(end<s.length()){int cp=s.codePointAt(end);int size=cp<128?10:20;if(used+size>width)break;used+=size;end+=Character.charCount(cp);}return end;}};
        TurnText.Document d=Boolean.parseBoolean(a[3])?TurnText.processing(label,measure):TurnText.document(label,body,measure,Boolean.parseBoolean(a[4]));
        int offset=d.clamp(Integer.parseInt(a[5]));
        StringBuilder out=new StringBuilder(d.lines.size()+"\t"+d.maxOffset()+"\t"+offset+"\t"+d.expanded());
        for(String row:d.window(offset))out.append('\t').append(encode(row));
        System.out.println(out);
      } else if(a[0].equals("progress"))System.out.println(RecordingFeedback.processingWidth(Long.parseLong(a[1])));
      else if(a[0].equals("meter")){RecordingFeedback f=new RecordingFeedback();f.sample(Integer.parseInt(a[1]));System.out.println(f.barHeight());}
      else if(a[0].equals("short"))System.out.println(RecordingFeedback.tooShort(Long.parseLong(a[1])));
      else if(a[0].equals("event"))System.out.println(UiContract.phaseForEvent(a[1])+"\t"+encode(UiContract.eventLabel(a[1])));
      else if(a[0].equals("stage"))System.out.println(UiContract.stage(decode(a[1])));
      else if(a[0].equals("fade")){TurnText.FooterFade f=new TurnText.FooterFade();f.setExpanded(true,0);System.out.println(f.alpha(Long.parseLong(a[1])));}
      else if(a[0].equals("menu"))System.out.println(UiContract.menuSelection(Integer.parseInt(a[1]),Integer.parseInt(a[2])));
      else throw new IllegalArgumentException("Unknown probe operation");
    }
  }
}
