/*
 * Copyright (C) 2017 De'vID jonpIn (David Yonge-Mallo)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tlhInganHol.android.klingonassistant;

import android.app.SearchManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.regex.Matcher;

public class EntryFragment extends Fragment {
  private String mEntryName = null;

  private static final int INTERMEDIATE_FLAGS =
      Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_INTERMEDIATE;
  private static final int FINAL_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

  // Static method for constructing EntryFragment.
  public static EntryFragment newInstance(Uri uri) {
    EntryFragment entryFragment = new EntryFragment();
    Bundle args = new Bundle();
    args.putString("uri", uri.toString());
    entryFragment.setArguments(args);
    return entryFragment;
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.entry, container, false);

    Resources resources = getActivity().getResources();

    TextView entryTitle = (TextView) rootView.findViewById(R.id.entry_title);
    TextView entryBody = (TextView) rootView.findViewById(R.id.entry_body);

    Uri uri = Uri.parse(getArguments().getString("uri"));

    // Retrieve the entry's data.
    // Note: managedQuery is deprecated since API 11.
    Cursor cursor =
        getActivity().managedQuery(uri, KlingonContentDatabase.ALL_KEYS, null, null, null);
    final KlingonContentProvider.Entry entry =
        new KlingonContentProvider.Entry(cursor, getActivity().getBaseContext());
    int entryId = entry.getId();

    // Handle alternative spellings here.
    if (entry.isAlternativeSpelling()) {
      // TODO: Immediate redirect to query in entry.getDefinition();
    }

    // Get the shared preferences.
    SharedPreferences sharedPrefs =
        PreferenceManager.getDefaultSharedPreferences(getActivity().getBaseContext());

    // Set the entry's name (along with info like "slang", formatted in HTML).
    entryTitle.invalidate();
    boolean useKlingonFont = Preferences.useKlingonFont(getActivity().getBaseContext());
    Typeface klingonTypeface =
        KlingonAssistant.getKlingonFontTypeface(getActivity().getBaseContext());
    if (useKlingonFont) {
      // Preference is set to display this in {pIqaD}!
      entryTitle.setText(entry.getFormattedEntryNameInKlingonFont());
    } else {
      // Boring transcription based on English (Latin) alphabet.
      entryTitle.setText(Html.fromHtml(entry.getFormattedEntryName(/* isHtml */ true)));
    }
    mEntryName = entry.getEntryName();

    // Set the colour for the entry name depending on its part of speech.
    entryTitle.setTextColor(entry.getTextColor());

    // Create the expanded definition.
    String pos = entry.getFormattedPartOfSpeech(/* isHtml */ false);
    String expandedDefinition = pos;

    // Determine whether to show the other-language definition. If shown, it is primary, and the
    // English definition is shown as secondary.
    String englishDefinition = entry.getDefinition();
    boolean displayOtherLanguageEntry = entry.shouldDisplayOtherLanguageDefinition();
    int englishDefinitionStart = -1;
    String englishDefinitionHeader = "\n" + resources.getString(R.string.label_english) + ": ";
    String otherLanguageDefinition = "";
    if (!displayOtherLanguageEntry) {
      // The simple case: just the English definition.
      expandedDefinition += englishDefinition;
    } else {
      // We display the other-language definition as the primary one, but keep track of the location
      // of the English definition to change its font size later.
      otherLanguageDefinition = entry.getOtherLanguageDefinition();
      expandedDefinition += otherLanguageDefinition;
      englishDefinitionStart = expandedDefinition.length();
      expandedDefinition += englishDefinitionHeader + englishDefinition;
    }

    // Experimental: Display other languages.
    final boolean showUnsupportedFeatures =
        sharedPrefs.getBoolean(
            Preferences.KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE, /* default */ false);
    if (!entry.isAlternativeSpelling() && showUnsupportedFeatures) {
      String definition_DE = entry.getDefinition_DE();
      String definition_FA = entry.getDefinition_FA();
      String definition_SV = entry.getDefinition_SV();
      String definition_RU = entry.getDefinition_RU();
      String definition_ZH_HK = entry.getDefinition_ZH_HK();
      String definition_PT = entry.getDefinition_PT();
      String definition_FI = entry.getDefinition_FI();

      // Show the other-language definition here only if it isn't already shown as the primary
      // definition (and the experimental flag is set to true).
      if (!definition_DE.equals("") && !definition_DE.equals(otherLanguageDefinition)) {
        expandedDefinition += "\nde: " + definition_DE;
      }
      if (!definition_FA.equals("") && !definition_FA.equals(otherLanguageDefinition)) {
        // Wrap Persian text with RLI/PDI pair.
        expandedDefinition += "\nfa: \u2067" + definition_FA + "\u2069";
      }
      if (!definition_SV.equals("") && !definition_SV.equals(otherLanguageDefinition)) {
        expandedDefinition += "\nsv: " + definition_SV;
      }
      if (!definition_RU.equals("") && !definition_RU.equals(otherLanguageDefinition)) {
        expandedDefinition += "\nru: " + definition_RU;
      }
      if (!definition_ZH_HK.equals("") && !definition_ZH_HK.equals(otherLanguageDefinition)) {
        expandedDefinition += "\nzh-HK: " + definition_ZH_HK;
      }
      if (!definition_PT.equals("") && !definition_PT.equals(otherLanguageDefinition)) {
        expandedDefinition += "\npt: " + definition_PT;
      }
      if (!definition_FI.equals("") && !definition_FI.equals(otherLanguageDefinition)) {
        expandedDefinition += "\nfi: " + definition_FI;
      }
    }

    // Show the basic notes.
    String notes;
    if (entry.shouldDisplayOtherLanguageNotes()) {
      notes = entry.getOtherLanguageNotes();
      if (notes.contains("[AUTOTRANSLATED]") || (showUnsupportedFeatures && !notes.equals(""))) {
        // In showUnsupportedFeatures mode, if the notes are suppressed, display a message so it's
        // clear that this is what's happened (i.e., not just that the non-English notes were
        // empty because they have not been translated), since the English notes will be displayed
        // in this mode.
        if (notes.equals("-")) {
          notes = "[English notes will not be shown in other language]";
        }
        // If notes are autotranslated, or unsupported features are enabled, display original
        // English notes also.
        notes += "\n\n" + entry.getNotes();
      } else if (notes.equals("-")) {
        // If the non-English notes is just the string "-", this indicates that the display of
        // notes should be suppressed. 
        notes = "";
      }
    } else {
      notes = entry.getNotes();
    }
    if (!notes.equals("")) {
      expandedDefinition += "\n\n" + notes;
    }

    // If this entry is hypothetical or extended canon, display warnings.
    if (entry.isHypothetical() || entry.isExtendedCanon()) {
      expandedDefinition += "\n\n";
      if (entry.isHypothetical()) {
        expandedDefinition += resources.getString(R.string.warning_hypothetical);
      }
      if (entry.isExtendedCanon()) {
        expandedDefinition += resources.getString(R.string.warning_extended_canon);
      }
    }

    // Show synonyms, antonyms, and related entries.
    String synonyms = entry.getSynonyms();
    String antonyms = entry.getAntonyms();
    String seeAlso = entry.getSeeAlso();
    if (!synonyms.equals("")) {
      expandedDefinition += "\n\n" + resources.getString(R.string.label_synonyms) + ": " + synonyms;
    }
    if (!antonyms.equals("")) {
      expandedDefinition += "\n\n" + resources.getString(R.string.label_antonyms) + ": " + antonyms;
    }
    if (!seeAlso.equals("")) {
      expandedDefinition += "\n\n" + resources.getString(R.string.label_see_also) + ": " + seeAlso;
    }

    // Display components if that field is not empty, unless we are showing an analysis link, in
    // which case we want to hide the components.
    boolean showAnalysis = entry.isSentence() || entry.isDerivative();
    String components = entry.getComponents();
    if (!components.equals("")) {
      // Treat the components column of inherent plurals and their
      // singulars differently than for other entries.
      if (entry.isInherentPlural()) {
        expandedDefinition +=
            "\n\n" + String.format(resources.getString(R.string.info_inherent_plural), components);
      } else if (entry.isSingularFormOfInherentPlural()) {
        expandedDefinition +=
            "\n\n" + String.format(resources.getString(R.string.info_singular_form), components);
      } else if (!showAnalysis) {
        // This is just a regular list of components.
        expandedDefinition +=
            "\n\n" + resources.getString(R.string.label_components) + ": " + components;
      }
    }

    // Display plural information.
    if (!entry.isPlural() && !entry.isInherentPlural() && !entry.isSingularFormOfInherentPlural()) {
      if (entry.isBeingCapableOfLanguage()) {
        expandedDefinition += "\n\n" + resources.getString(R.string.info_being);
      } else if (entry.isBodyPart()) {
        expandedDefinition += "\n\n" + resources.getString(R.string.info_body);
      }
    }

    // If the entry is a useful phrase, link back to its category.
    if (entry.isSentence()) {
      String sentenceType = entry.getSentenceType();
      if (!sentenceType.equals("")) {
        // Put the query as a placeholder for the actual category.
        expandedDefinition +=
            "\n\n"
                + resources.getString(R.string.label_category)
                + ": {"
                + entry.getSentenceTypeQuery()
                + "}";
      }
    }

    // If the entry is a sentence, make a link to analyse its components.
    if (showAnalysis) {
      String analysisQuery = entry.getEntryName();
      if (!components.equals("")) {
        // Strip the brackets around each component so they won't be processed.
        analysisQuery += ":" + entry.getPartOfSpeech();
        int homophoneNumber = entry.getHomophoneNumber();
        if (homophoneNumber != -1) {
          analysisQuery += ":" + homophoneNumber;
        }
        analysisQuery +=
            KlingonContentProvider.Entry.COMPONENTS_MARKER + components.replaceAll("[{}]", "");
      }
      expandedDefinition +=
          "\n\n" + resources.getString(R.string.label_analyze) + ": {" + analysisQuery + "}";
    }

    // Show the examples.
    String examples;
    if (entry.shouldDisplayOtherLanguageExamples()) {
      examples = entry.getOtherLanguageExamples();
    } else {
      examples = entry.getExamples();
    }
    if (!examples.equals("")) {
      expandedDefinition += "\n\n" + resources.getString(R.string.label_examples) + ": " + examples;
    }

    // Show the source.
    String source = entry.getSource();
    if (!source.equals("")) {
      expandedDefinition += "\n\n" + resources.getString(R.string.label_sources) + ": " + source;
    }

    // If this is a verb (but not a prefix or suffix), show the transitivity information.
    String transitivity = "";
    if (entry.isVerb()
        && sharedPrefs.getBoolean(
            Preferences.KEY_SHOW_TRANSITIVITY_CHECKBOX_PREFERENCE, /* default */ true)) {
      // This is a verb and show transitivity preference is set to true.
      transitivity = entry.getTransitivityString();
    }
    int transitivityStart = -1;
    String transitivityHeader = "\n\n" + resources.getString(R.string.label_transitivity) + ": ";
    boolean showTransitivityInformation = !transitivity.equals("");
    if (showTransitivityInformation) {
      transitivityStart = expandedDefinition.length();
      expandedDefinition += transitivityHeader + transitivity;
    }

    // Show the hidden notes.
    String hiddenNotes = "";
    if (sharedPrefs.getBoolean(
        Preferences.KEY_SHOW_ADDITIONAL_INFORMATION_CHECKBOX_PREFERENCE, /* default */ true)) {
      // Show additional information preference set to true.
      hiddenNotes = entry.getHiddenNotes();
    }
    int hiddenNotesStart = -1;
    String hiddenNotesHeader =
        "\n\n" + resources.getString(R.string.label_additional_information) + ": ";
    if (!hiddenNotes.equals("")) {
      hiddenNotesStart = expandedDefinition.length();
      expandedDefinition += hiddenNotesHeader + hiddenNotes;
    }

    // Format the expanded definition, including linkifying the links to other entries.
    // We add a newline to the end of the definition because if there is a link on the final line,
    // its tap target target expands to the bottom of the TextView.
    float smallTextScale = (float) 0.8;
    SpannableStringBuilder ssb = new SpannableStringBuilder(expandedDefinition + "\n");
    if (!pos.equals("")) {
      // Italicise the part of speech.
      ssb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, pos.length(), FINAL_FLAGS);
    }
    if (displayOtherLanguageEntry) {
      // Reduce the size of the secondary (English) definition.
      ssb.setSpan(
          new RelativeSizeSpan(smallTextScale),
          englishDefinitionStart,
          englishDefinitionStart + englishDefinitionHeader.length() + englishDefinition.length(),
          FINAL_FLAGS);
    }
    if (showTransitivityInformation) {
      // Reduce the size of the transitivity information.
      ssb.setSpan(
          new RelativeSizeSpan(smallTextScale),
          transitivityStart,
          transitivityStart + transitivityHeader.length() + transitivity.length(),
          FINAL_FLAGS);
    }
    if (!hiddenNotes.equals("")) {
      // Reduce the size of the hidden notes.
      ssb.setSpan(
          new RelativeSizeSpan(smallTextScale),
          hiddenNotesStart,
          hiddenNotesStart + hiddenNotesHeader.length() + hiddenNotes.length(),
          FINAL_FLAGS);
    }
    processMixedText(ssb, entry);

    // Display the entry name and definition.
    entryBody.invalidate();
    entryBody.setText(ssb);
    entryBody.setMovementMethod(LinkMovementMethod.getInstance());

    return rootView;
  }

  // Helper function to process text that includes Klingon text.
  protected void processMixedText(SpannableStringBuilder ssb, KlingonContentProvider.Entry entry) {
    float smallTextScale = (float) 0.8;
    boolean useKlingonFont = Preferences.useKlingonFont(getActivity().getBaseContext());
    Typeface klingonTypeface =
        KlingonAssistant.getKlingonFontTypeface(getActivity().getBaseContext());

    String mixedText = ssb.toString();
    Matcher m = KlingonContentProvider.Entry.ENTRY_PATTERN.matcher(mixedText);
    while (m.find()) {

      // Strip the brackets {} to get the query.
      String query = mixedText.substring(m.start() + 1, m.end() - 1);
      LookupClickableSpan viewLauncher = new LookupClickableSpan(query);

      // Process the linked entry information.
      KlingonContentProvider.Entry linkedEntry =
          new KlingonContentProvider.Entry(query, getActivity().getBaseContext());
      // Log.d(TAG, "linkedEntry.getEntryName() = " + linkedEntry.getEntryName());

      // Delete the brackets and metadata parts of the string (which includes analysis components).
      ssb.delete(m.start() + 1 + linkedEntry.getEntryName().length(), m.end());
      ssb.delete(m.start(), m.start() + 1);
      int end = m.start() + linkedEntry.getEntryName().length();

      // Insert link to the category for a useful phrase.
      if (entry != null
          && entry.isSentence()
          && !entry.getSentenceType().equals("")
          && linkedEntry.getEntryName().equals("*")) {
        // Delete the "*" placeholder.
        ssb.delete(m.start(), m.start() + 1);

        // Insert the category name.
        ssb.insert(m.start(), entry.getSentenceType());
        end += entry.getSentenceType().length() - 1;
      }

      // Set the font and link.
      // This is true if this entry doesn't launch an EntryActivity. Don't link to an entry if the
      // current text isn't an entry, or there is an explicit "nolink" tag, or the link opens a URL
      // (either a source link or a direct URL link).
      boolean disableEntryLink =
          (entry == null)
              || linkedEntry.doNotLink()
              || linkedEntry.isSource()
              || linkedEntry.isURL();
      // The last span set on a range must have FINAL_FLAGS.
      int maybeFinalFlags = disableEntryLink ? FINAL_FLAGS : INTERMEDIATE_FLAGS;
      if (linkedEntry.isSource()) {
        // If possible, link to the source.
        String url = linkedEntry.getURL();
        if (!url.equals("")) {
          ssb.setSpan(new URLSpan(url), m.start(), end, INTERMEDIATE_FLAGS);
        }
        // Names of sources are in italics.
        ssb.setSpan(
            new StyleSpan(android.graphics.Typeface.ITALIC), m.start(), end, maybeFinalFlags);
      } else if (linkedEntry.isURL()) {
        // Linkify URL if there is one.
        String url = linkedEntry.getURL();
        if (!url.equals("")) {
          ssb.setSpan(new URLSpan(url), m.start(), end, maybeFinalFlags);
        }
      } else if (useKlingonFont) {
        // Display the text using the Klingon font. Categories (which have an entry of "*") must
        // be handled specially.
        boolean replaceWithKlingonFontText = false;
        String klingonEntryName = null;
        if (!linkedEntry.getEntryName().equals("*")) {
          // This is just regular Klingon text. Display it in Klingon font.
          klingonEntryName = linkedEntry.getEntryNameInKlingonFont();
          replaceWithKlingonFontText = true;
        } else if (Preferences.useKlingonUI(getActivity().getBaseContext())) {
          // This is a category, and the option to use Klingon UI is set, so this will be in
          // Klingon.
          // Display it in Klingon font.
          klingonEntryName =
              KlingonContentProvider.convertStringToKlingonFont(entry.getSentenceType());
          replaceWithKlingonFontText = true;
        } else {
          // This is a category, but the option to use Klingon UI is not set, so this will be in the
          // system language.
          // Leave it alone.
          replaceWithKlingonFontText = false;
        }
        if (replaceWithKlingonFontText) {
          ssb.delete(m.start(), end);
          ssb.insert(m.start(), klingonEntryName);
          end = m.start() + klingonEntryName.length();
          ssb.setSpan(
              new KlingonTypefaceSpan("", klingonTypeface), m.start(), end, maybeFinalFlags);
        }
      } else {
        // Klingon is in bold serif.
        ssb.setSpan(
            new StyleSpan(android.graphics.Typeface.BOLD), m.start(), end, INTERMEDIATE_FLAGS);
        ssb.setSpan(new TypefaceSpan("serif"), m.start(), end, maybeFinalFlags);
      }
      // If linked entry is hypothetical or extended canon, insert a "?" in front.
      if (linkedEntry.isHypothetical() || linkedEntry.isExtendedCanon()) {
        ssb.insert(m.start(), "?");
        ssb.setSpan(
            new RelativeSizeSpan(smallTextScale), m.start(), m.start() + 1, INTERMEDIATE_FLAGS);
        ssb.setSpan(new SuperscriptSpan(), m.start(), m.start() + 1, maybeFinalFlags);
        end++;
      }

      // For a suffix, protect the hyphen from being separated from the rest of the suffix.
      if (ssb.charAt(m.start()) == '-') {
        // U+2011 is the non-breaking hyphen.
        ssb.replace(m.start(), m.start() + 1, "\u2011");
      }

      // Only apply colours to verbs, nouns, and affixes (exclude BLUE and WHITE).
      if (!disableEntryLink) {
        // Link to view launcher.
        ssb.setSpan(viewLauncher, m.start(), end, INTERMEDIATE_FLAGS);
      }
      // Set the colour last, so it's not overridden by other spans.
      // There is a bug in Android 6 (API 23) and 7 (API 24 and 25) which
      // messes up the sort order of the ForegroundColorSpans.
      // See: https://github.com/De7vID/klingon-assistant/issues/190
      // The work-around does not work when running on Chromebook (version
      // 61.0.3163.120).
      ForegroundColorSpan[] oldSpans = ssb.getSpans(m.start(), end, ForegroundColorSpan.class);
      for (ForegroundColorSpan span : oldSpans) {
        ssb.removeSpan(span);
      }
      ssb.setSpan(new ForegroundColorSpan(linkedEntry.getTextColor()), m.start(), end, FINAL_FLAGS);
      String linkedPos = linkedEntry.getBracketedPartOfSpeech(/* isHtml */ false);
      if (!linkedPos.equals("") && linkedPos.length() > 1) {
        ssb.insert(end, linkedPos);

        int rightBracketLoc = linkedPos.indexOf(")");
        if (rightBracketLoc != -1) {
          // linkedPos is always of the form " (pos)[ (def'n N)]", we want to italicise
          // the "pos" part only.
          ssb.setSpan(
              new StyleSpan(android.graphics.Typeface.ITALIC),
              end + 2,
              end + rightBracketLoc,
              FINAL_FLAGS);
        }
      }

      // Rinse and repeat.
      mixedText = ssb.toString();
      m = KlingonContentProvider.Entry.ENTRY_PATTERN.matcher(mixedText);
    }
  }

  // Private class for handling clickable spans.
  private class LookupClickableSpan extends ClickableSpan {
    private String mQuery;

    LookupClickableSpan(String query) {
      mQuery = query;
    }

    @Override
    public void onClick(View view) {
      Intent intent = new Intent(view.getContext(), KlingonAssistant.class);
      intent.setAction(Intent.ACTION_SEARCH);
      // Internal searches are preceded by a plus to disable "xifan hol" mode.
      intent.putExtra(SearchManager.QUERY, "+" + mQuery);

      view.getContext().startActivity(intent);
    }
  }
}
