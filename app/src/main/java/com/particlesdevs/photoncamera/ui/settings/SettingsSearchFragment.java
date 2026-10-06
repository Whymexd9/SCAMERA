package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

/** Search keeps navigation separate from editing: a hit opens the real preference page. */
public class SettingsSearchFragment extends Fragment {
    public static final class Entry implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String key, page, title, path, summary;
        Entry(String key, String page, String title, String path, String summary) {
            this.key=key; this.page=page; this.title=title; this.path=path; this.summary=summary;
        }
        public boolean matches(String query) { return matches(query, null); }
        /** As {@link #matches(String)}, also looking in {@code other}: the words of this setting in the other UI language. */
        public boolean matches(String query, String other) {
            String haystack=normalize(title+" "+path+" "+summary+" "+key+(other==null ? "" : " "+other));
            for(String word:normalize(query).trim().split("\\s+")) if(!haystack.contains(word)) return false;
            return true;
        }
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replace('ё','е'); }
    /**
     * Title, summary and page titles of every XML setting in the other UI language (English on a Russian UI, Russian
     * otherwise), by key: the search finds a setting by its Russian and by its English words whatever the UI language.
     * Read straight from {@code R.xml.preferences} with the resources of the other locale, so no preference is built;
     * settings generated in code have no entry and are found by their shown words only.
     */
    static HashMap<String,String> otherLanguage(Context context) {
        HashMap<String,String> words=new HashMap<>();
        if(context==null) return words;
        try {
            Configuration config=new Configuration(context.getResources().getConfiguration());
            config.setLocale(Lang.ru(context) ? Locale.ENGLISH : Locale.forLanguageTag("ru"));
            Resources res=context.createConfigurationContext(config).getResources();
            try(XmlResourceParser xml=res.getXml(R.xml.preferences)) {
                ArrayList<String> pages=new ArrayList<>();
                for(int event=xml.next(); event!=XmlPullParser.END_DOCUMENT; event=xml.next()) {
                    if(event==XmlPullParser.START_TAG) {
                        String key=null, title="", summary="";
                        for(int i=0;i<xml.getAttributeCount();i++) {
                            String name=xml.getAttributeName(i);
                            if("key".equals(name)) key=attribute(res, xml, i);
                            else if("title".equals(name)) title=attribute(res, xml, i);
                            else if("summary".equals(name)) summary=attribute(res, xml, i);
                        }
                        if(key!=null && !title.isEmpty()) words.put(key, title+" "+String.join(" ", pages)+" "+summary);
                        pages.add(xml.getDepth()==1 ? "" : title); // as index(): the root title is not part of a path
                    } else if(event==XmlPullParser.END_TAG && !pages.isEmpty()) pages.remove(pages.size()-1);
                }
            }
        } catch(Exception | LinkageError ignored) {
            // no words of the other language: the shown words are still searched
        }
        return words;
    }
    private static String attribute(Resources res, XmlResourceParser xml, int index) {
        int id=xml.getAttributeResourceValue(index, 0);
        if(id!=0) {
            try { return res.getString(id); } catch(RuntimeException notText) { return ""; }
        }
        String value=xml.getAttributeValue(index);
        return value==null ? "" : value;
    }
    public static ArrayList<Entry> index(PreferenceGroup root) {
        ArrayList<Entry> entries=new ArrayList<>();
        collect(root, root.getKey(), "", entries);
        return entries;
    }
    private static void collect(PreferenceGroup group, String page, String path, ArrayList<Entry> entries) {
        for(int i=0;i<group.getPreferenceCount();i++) {
            Preference p=group.getPreference(i);
            if(!p.isVisible()) continue;
            String title=p.getTitle()==null ? "" : p.getTitle().toString();
            String key=p.getKey();
            if(key!=null && !title.isEmpty()) {
                CharSequence summary=p.getSummary();
                entries.add(new Entry(key, p instanceof PreferenceScreen ? key : page, title, path,
                        summary==null ? "" : summary.toString()));
            }
            if(p instanceof PreferenceGroup) collect((PreferenceGroup)p,
                    p instanceof PreferenceScreen ? key : page,
                    path.isEmpty() ? title : path+" › "+title, entries);
        }
    }
    public static SettingsSearchFragment create(ArrayList<Entry> entries) {
        SettingsSearchFragment fragment=new SettingsSearchFragment();
        Bundle args=new Bundle(); args.putSerializable("entries", entries); fragment.setArguments(args);
        return fragment;
    }
    private String query="";
    private ArrayList<Entry> entries;
    private HashMap<String,String> other=new HashMap<>(); // the words of the other UI language, by key
    private final ArrayList<Entry> results=new ArrayList<>();
    private EditText input;
    private TextView count;
    private RecyclerView list;
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView v=new TextView(requireContext()); v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;
    }
    @SuppressWarnings("unchecked")
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        entries=(ArrayList<Entry>)requireArguments().getSerializable("entries");
        if(entries==null) entries=new ArrayList<>();
        if(state!=null) query=state.getString("query", "");
        other=otherLanguage(getContext());
    }
    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),0); root.setBackgroundColor(SettingsStyle.BG);
        // P6b: the shared header and a field in the card style
        SettingsStyle.Header header=SettingsStyle.header(requireContext(),()->requireActivity().getOnBackPressedDispatcher().onBackPressed(),null);
        header.heading.setText(Lang.t(getContext(),"Поиск настройки","Search settings"));header.subtitle.setVisibility(View.GONE);root.addView(header.view);
        LinearLayout search=new LinearLayout(requireContext());search.setGravity(android.view.Gravity.CENTER_VERTICAL);
        search.setBackground(SettingsStyle.shape(requireContext(),SettingsStyle.FIELD,SettingsStyle.LINE,14));search.setPadding(dp(14),0,dp(4),0);
        android.widget.ImageView glass=new android.widget.ImageView(requireContext());glass.setImageResource(R.drawable.settings_ic_search);
        glass.setColorFilter(SettingsStyle.MUTED);search.addView(glass,new LinearLayout.LayoutParams(dp(22),dp(22)));
        input=new EditText(requireContext());input.setSingleLine(true);input.setTextColor(SettingsStyle.TEXT);input.setBackground(null);
        input.setHintTextColor(SettingsStyle.MUTED);input.setTextSize(17);input.setHint(Lang.t(getContext(),"Поиск настройки","Search settings"));
        input.setContentDescription(Lang.t(getContext(),"Поиск настройки","Search settings"));input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addView(input,new LinearLayout.LayoutParams(0,dp(56),1));
        TextView clear=label("×",28,com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.color(requireContext()));clear.setGravity(android.view.Gravity.CENTER);
        clear.setContentDescription(Lang.t(getContext(),"Очистить поиск","Clear the search"));clear.setOnClickListener(v->input.setText(""));
        search.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(search);
        count=label("",13,SettingsStyle.MUTED);count.setPadding(0,dp(14),0,dp(12));root.addView(count);
        list=new RecyclerView(requireContext());list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new ResultsAdapter());root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        input.setText(query);input.setSelection(input.length());filter(query);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();filter(query);}
            public void afterTextChanged(Editable e){}
        });
        return root;
    }
    private void filter(String text) {
        results.clear();
        if(!text.trim().isEmpty()) for(Entry e:entries) if(e.matches(text, other.get(e.key)))results.add(e);
        count.setText(text.trim().isEmpty() ? Lang.t(getContext(),"Введите название или описание настройки","Type the name or description of a setting") :
                results.isEmpty() ? Lang.t(getContext(),"Ничего не найдено","Nothing found") : Lang.t(getContext(),"Найдено: ","Found: ")+results.size());
        list.getAdapter().notifyDataSetChanged();
    }
    @Override public void onResume() {
        super.onResume();View toolbar=requireActivity().findViewById(R.id.settings_toolbar);
        if(toolbar!=null)toolbar.setVisibility(View.GONE);
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state){super.onSaveInstanceState(state);state.putString("query",query);}
    @Override public void onDestroyView(){input=null;count=null;list=null;super.onDestroyView();}
    private CharSequence highlighted(String text) {
        SpannableString s=new SpannableString(text);String normalized=normalize(text);
        for(String word:normalize(query).trim().split("\\s+")) {
            if(word.isEmpty())continue;
            for(int at=normalized.indexOf(word);at>=0;at=normalized.indexOf(word,at+word.length()))
                s.setSpan(new ForegroundColorSpan(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.color(requireContext())),at,at+word.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
    private class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final TextView title,path;
            Holder(LinearLayout row,TextView title,TextView path){super(row);this.title=title;this.path=path;}
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            LinearLayout row=new LinearLayout(requireContext());row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16),dp(13),dp(12),dp(13));row.setMinimumHeight(dp(64));row.setFocusable(true);
            row.setBackgroundResource(R.drawable.settings_card); // the same card as the preference rows
            TextView title=label("",16,SettingsStyle.TEXT),path=label("",12,SettingsStyle.MUTED);
            path.setPadding(0,dp(5),0,0);row.addView(title);row.addView(path);
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);lp.topMargin=dp(10);
            row.setLayoutParams(lp);return new Holder(row,title,path);
        }
        @Override public void onBindViewHolder(@NonNull Holder h,int position){
            Entry e=results.get(position);h.title.setText(highlighted(e.title));h.path.setText(e.path);
            h.itemView.setOnClickListener(v->{
                InputMethodManager keyboard=(InputMethodManager)requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if(keyboard!=null)keyboard.hideSoftInputFromWindow(v.getWindowToken(),0);
                ((SettingsActivity)requireActivity()).openSearchResult(e);
            });
        }
        @Override public int getItemCount(){return results.size();}
    }
}
