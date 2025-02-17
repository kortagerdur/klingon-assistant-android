/*
 * Copyright (C) 2014 De'vID jonpIn (David Yonge-Mallo)
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
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.graphics.Typeface;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provides access to the dictionary database. */
public class KlingonContentProvider extends ContentProvider {
  private static final String TAG = "KlingonContentProvider";

  public static String AUTHORITY =
      "org.tlhInganHol.android.klingonassistant.KlingonContentProvider";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

  // MIME types used for searching entries or looking up a single definition
  public static final String ENTRIES_MIME_TYPE =
      ContentResolver.CURSOR_DIR_BASE_TYPE + "/org.tlhInganHol.android.klingonassistant";
  public static final String DEFINITION_MIME_TYPE =
      ContentResolver.CURSOR_ITEM_BASE_TYPE + "/org.tlhInganHol.android.klingonassistant";

  /**
   * The columns we'll include in our search suggestions. There are others that could be used to
   * further customize the suggestions, see the docs in {@link SearchManager} for the details on
   * additional columns that are supported.
   */
  private static final String[] SUGGESTION_COLUMNS = {
    BaseColumns._ID, // must include this column
    SearchManager.SUGGEST_COLUMN_TEXT_1,
    SearchManager.SUGGEST_COLUMN_TEXT_2,
    SearchManager.SUGGEST_COLUMN_INTENT_DATA,
    // SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
  };

  // The actual Klingon Content Database.
  private KlingonContentDatabase mContentDatabase;

  // UriMatcher stuff
  private static final int SEARCH_ENTRIES = 0;
  private static final int GET_ENTRY = 1;
  private static final int SEARCH_SUGGEST = 2;
  private static final int REFRESH_SHORTCUT = 3;
  private static final int GET_ENTRY_BY_ID = 4;
  private static final int GET_RANDOM_ENTRY = 5;
  private static final UriMatcher sURIMatcher = buildUriMatcher();

  /** Builds up a UriMatcher for search suggestion and shortcut refresh queries. */
  private static UriMatcher buildUriMatcher() {
    UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    // to get definitions...
    matcher.addURI(AUTHORITY, "lookup", SEARCH_ENTRIES);
    // by database row (which is not the same as its id)...
    matcher.addURI(AUTHORITY, "lookup/#", GET_ENTRY);
    // to get suggestions...
    matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST);
    matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", SEARCH_SUGGEST);

    // This is needed internally to get an entry by its id.
    matcher.addURI(AUTHORITY, "get_entry_by_id/#", GET_ENTRY_BY_ID);

    // This is needed internally to get a random entry.
    matcher.addURI(AUTHORITY, "get_random_entry", GET_RANDOM_ENTRY);

    /*
     * The following are unused in this implementation, but if we include {@link
     * SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we could
     * expect to receive refresh queries when a shortcutted suggestion is displayed in Quick Search
     * Box, in which case, the following Uris would be provided and we would return a cursor with a
     * single item representing the refreshed suggestion data.
     */
    matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT, REFRESH_SHORTCUT);
    matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/*", REFRESH_SHORTCUT);
    return matcher;
  }

  @Override
  public boolean onCreate() {
    mContentDatabase = new KlingonContentDatabase(getContext());
    return true;
  }

  /**
   * Handles all the database searches and suggestion queries from the Search Manager. When
   * requesting a specific entry, the uri alone is required. When searching all of the database for
   * matches, the selectionArgs argument must carry the search query as the first element. All other
   * arguments are ignored.
   */
  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

    // Use the UriMatcher to see what kind of query we have and format the db query accordingly
    switch (sURIMatcher.match(uri)) {
      case SEARCH_SUGGEST:
        // Uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
        if (selectionArgs == null) {
          throw new IllegalArgumentException("selectionArgs must be provided for the Uri: " + uri);
        }
        return getSuggestions(selectionArgs[0]);
      case SEARCH_ENTRIES:
        // Uri has "/lookup".
        if (selectionArgs == null) {
          throw new IllegalArgumentException("selectionArgs must be provided for the Uri: " + uri);
        }
        return search(selectionArgs[0]);
      case GET_ENTRY:
        return getEntry(uri);
      case REFRESH_SHORTCUT:
        return refreshShortcut(uri);
      case GET_ENTRY_BY_ID:
        // This case was added to allow getting the entry by its id.
        String entryId = null;
        if (uri.getPathSegments().size() > 1) {
          entryId = uri.getLastPathSegment();
        }
        // Log.d(TAG, "entryId = " + entryId);
        return getEntryById(entryId, projection);
      case GET_RANDOM_ENTRY:
        return getRandomEntry(projection);
      default:
        throw new IllegalArgumentException("Unknown Uri: " + uri);
    }
  }

  // (1) - This is the first way the database can be queried.
  // Called when uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
  // This populates the dropdown list from the search box.
  private Cursor getSuggestions(String query) {
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "getSuggestions called with query: \"" + query + "\"");
    }
    if (query.equals("")) {
      return null;
    }

    // First, get all the potentially relevant entries. Include all columns of data.
    Cursor rawCursor = mContentDatabase.getEntryMatches(query);

    // Format to two columns for display.
    MatrixCursor formattedCursor = new MatrixCursor(SUGGESTION_COLUMNS);
    if (rawCursor.getCount() != 0) {
      rawCursor.moveToFirst();
      do {
        formattedCursor.addRow(formatEntryForSearchResults(rawCursor));
      } while (rawCursor.moveToNext());
    }
    return formattedCursor;
  }

  private Object[] formatEntryForSearchResults(Cursor cursor) {
    // Format the search results for display here. These are the two-line dropdown results from the
    // search box. We fully indent suffixes, but only half-indent verbs when they have a prefix.
    Entry entry = new Entry(cursor, getContext());
    int entryId = entry.getId();
    String indent1 = entry.isIndented() ? (entry.isVerb() ? "  " : "    ") : "";
    String indent2 = entry.isIndented() ? (entry.isVerb() ? "   " : "      ") : "";
    String entryName = indent1 + entry.getFormattedEntryName(/* isHtml */ false);
    String formattedDefinition = indent2 + entry.getFormattedDefinition(/* isHtml */ false);
    // TODO: Format the "alt" results.

    // Search suggestions must have exactly four columns in exactly this format.
    return new Object[] {
      entryId, // _id
      entryName, // text1
      formattedDefinition, // text2
      entryId, // intent_data (included when clicking on item)
    };
  }

  // (2) - This is the second way the database can be queried.
  // Called when uri has "/lookup".
  // Either we're following a link, or the user has pressed the "Go" button from search.
  private Cursor search(String query) {
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "search called with query: " + query);
    }

    return mContentDatabase.getEntryMatches(query);
  }

  private Cursor getEntry(Uri uri) {
    // Log.d(TAG, "getEntry called with uri: " + uri.toString());
    String rowId = uri.getLastPathSegment();
    return mContentDatabase.getEntry(rowId, KlingonContentDatabase.ALL_KEYS);
  }

  private Cursor refreshShortcut(Uri uri) {
    /*
     * This won't be called with the current implementation, but if we include {@link
     * SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we could
     * expect to receive refresh queries when a shortcutted suggestion is displayed in Quick Search
     * Box. In which case, this method will query the table for the specific entry, using the given
     * item Uri and provide all the columns originally provided with the suggestion query.
     */
    String rowId = uri.getLastPathSegment();
    /*
     * String[] columns = new String[] { KlingonContentDatabase.KEY_ID,
     * KlingonContentDatabase.KEY_ENTRY_NAME, KlingonContentDatabase.KEY_DEFINITION, // Add other
     * keys here. SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
     * SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID};
     */

    return mContentDatabase.getEntry(rowId, KlingonContentDatabase.ALL_KEYS);
  }

  /** Retrieve a single entry by its _id. */
  private Cursor getEntryById(String entryId, String[] projection) {
    // Log.d(TAG, "getEntryById called with entryid: " + entryId);
    return mContentDatabase.getEntryById(entryId, projection);
  }

  /** Get a single random entry. */
  private Cursor getRandomEntry(String[] projection) {
    return mContentDatabase.getRandomEntry(projection);
  }

  /**
   * This method is required in order to query the supported types. It's also useful in our own
   * query() method to determine the type of Uri received.
   */
  @Override
  public String getType(Uri uri) {
    switch (sURIMatcher.match(uri)) {
      case SEARCH_ENTRIES:
        return ENTRIES_MIME_TYPE;
      case GET_ENTRY:
        return DEFINITION_MIME_TYPE;
      case SEARCH_SUGGEST:
        return SearchManager.SUGGEST_MIME_TYPE;
      case REFRESH_SHORTCUT:
        return SearchManager.SHORTCUT_MIME_TYPE;
      default:
        throw new IllegalArgumentException("Unknown URL " + uri);
    }
  }

  // Other required implementations...

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    throw new UnsupportedOperationException();
  }

  public static String convertStringToKlingonFont(String s) {
    // Strip anything we don't recognise.
    // This pattern should be kept mostly in sync with ENTRY_PATTERN. Note that "ü" and "+" will
    // never be in an entry name.
    String klingonString = s.replaceAll("[^A-Za-z0-9 '\\\":;,\\.\\-?!_/()@=%&\\*]", "");

    // This is a hack: change the separators between words and their affixes.
    // TODO: Do this upstream and colour the affixes differently.
    klingonString =
        klingonString
            .replaceAll(" + -", " ◃ ")
            .replaceAll("- + ", " ▹ ")
            .replaceAll("^-", "◃ ")
            .replaceAll("-$", " ▹");

    // {gh} must come before {ngh} since {ngh} is {n} + {gh} and not {ng} + *{h}.
    // {ng} must come before {n}.
    // {tlh} must come before {t} and {l}.
    // Don't change {-} since it's needed for prefixes and suffixes.
    // Don't change "..." (ellipses), but do change "." (periods).
    klingonString =
        klingonString
            .replaceAll("gh", "")
            .replaceAll("ng", "")
            .replaceAll("tlh", "")
            .replaceAll("a", "")
            .replaceAll("b", "")
            .replaceAll("ch", "")
            .replaceAll("D", "")
            .replaceAll("e", "")
            .replaceAll("H", "")
            .replaceAll("I", "")
            .replaceAll("j", "")
            .replaceAll("l", "")
            .replaceAll("m", "")
            .replaceAll("n", "")
            .replaceAll("o", "")
            .replaceAll("p", "")
            .replaceAll("q", "")
            .replaceAll("Q", "")
            .replaceAll("r", "")
            .replaceAll("S", "")
            .replaceAll("t", "")
            .replaceAll("u", "")
            .replaceAll("v", "")
            .replaceAll("w", "")
            .replaceAll("y", "")
            .replaceAll("'", "")
            .replaceAll("0", "")
            .replaceAll("1", "")
            .replaceAll("2", "")
            .replaceAll("3", "")
            .replaceAll("4", "")
            .replaceAll("5", "")
            .replaceAll("6", "")
            .replaceAll("7", "")
            .replaceAll("8", "")
            .replaceAll("9", "")
            .replaceAll(",", "")
            .replaceAll(";", "")
            .replaceAll("!", "")
            .replaceAll("\\(", "▹")
            .replaceAll("\\)", "◃")
            .replaceAll("-", "◃")
            .replaceAll("\\?", "")
            .replaceAll("\\.", "")
            // Note: The LHS is in Klingon due to previous replacements.
            // We replace three periods in a row with an ellipsis.
            .replaceAll("", "⋯");
    return klingonString;
  }

  // This class is for managing entries.
  public static class Entry {
    // The logging tag can be at most 23 characters. "KlingonContentProvider.Entry" was too long.
    String TAG = "KCP.Entry";

    // Pattern for matching entry in text. The letter "ü" is needed to match "Saarbrücken". The "+"
    // is needed for "Google+".
    public static Pattern ENTRY_PATTERN =
        Pattern.compile("\\{[A-Za-zü0-9 '\\\":;,\\.\\-?!_/()@=%&\\*\\+]+\\}");

    // Used for analysis of entries with components.
    // It cannot occur in a link (we cannot use "//" for example because it occurs in URL links,
    // or one "@" because it occurs in email addresses). It should not contain anything that
    // needs to be escaped in a regular expression, since it is stripped and then added back.
    public static final String COMPONENTS_MARKER = "@@";

    // Context.
    private Context mContext;

    // The raw data for the entry.
    // private Uri mUri = null;
    private int mId = -1;
    private String mEntryName = "";
    private String mPartOfSpeech = "";
    private String mDefinition = "";
    private String mSynonyms = "";
    private String mAntonyms = "";
    private String mSeeAlso = "";
    private String mNotes = "";
    private String mHiddenNotes = "";
    private String mComponents = "";
    private String mExamples = "";
    private String mSearchTags = "";
    private String mSource = "";

    // Localised definitions.
    private String mDefinition_DE = "";
    private String mNotes_DE = "";
    private String mExamples_DE = "";
    private String mSearchTags_DE = "";
    private String mDefinition_FA = "";
    private String mNotes_FA = "";
    private String mExamples_FA = "";
    private String mSearchTags_FA = "";
    private String mDefinition_SV = "";
    private String mNotes_SV = "";
    private String mExamples_SV = "";
    private String mSearchTags_SV = "";
    private String mDefinition_RU = "";
    private String mNotes_RU = "";
    private String mExamples_RU = "";
    private String mSearchTags_RU = "";
    private String mDefinition_ZH_HK = "";
    private String mNotes_ZH_HK = "";
    private String mExamples_ZH_HK = "";
    private String mSearchTags_ZH_HK = "";
    private String mDefinition_PT = "";
    private String mNotes_PT = "";
    private String mExamples_PT = "";
    private String mSearchTags_PT = "";
    private String mDefinition_FI = "";
    private String mNotes_FI = "";
    private String mExamples_FI = "";
    private String mSearchTags_FI = "";

    // Part of speech metadata.
    private enum BasePartOfSpeechEnum {
      NOUN,
      VERB,
      ADVERBIAL,
      CONJUNCTION,
      QUESTION,
      SENTENCE,
      EXCLAMATION,
      SOURCE,
      URL,
      UNKNOWN
    }

    private String[] basePartOfSpeechAbbreviations = {
      "n", "v", "adv", "conj", "ques", "sen", "excl", "src", "url", "???"
    };
    private BasePartOfSpeechEnum mBasePartOfSpeech = BasePartOfSpeechEnum.UNKNOWN;

    // Verb attributes.
    private enum VerbTransitivityType {
      TRANSITIVE,
      INTRANSITIVE,
      STATIVE,
      AMBITRANSITIVE,
      UNKNOWN,
      HAS_TYPE_5_NOUN_SUFFIX
    }

    private VerbTransitivityType mTransitivity = VerbTransitivityType.UNKNOWN;
    boolean mTransitivityConfirmed = false;

    // Noun attributes.
    private enum NounType {
      GENERAL,
      NUMBER,
      NAME,
      PRONOUN
    }

    private NounType mNounType = NounType.GENERAL;
    boolean mIsInherentPlural = false;
    boolean mIsSingularFormOfInherentPlural = false;
    boolean mIsPlural = false;

    // Sentence types.
    private enum SentenceType {
      PHRASE,
      EMPIRE_UNION_DAY,
      CURSE_WARFARE,
      IDIOM,
      NENTAY,
      PROVERB,
      MILITARY_CELEBRATION,
      REJECTION,
      REPLACEMENT_PROVERB,
      SECRECY_PROVERB,
      TOAST,
      LYRICS,
      BEGINNERS_CONVERSATION,
      JOKE
    }

    private SentenceType mSentenceType = SentenceType.PHRASE;

    // Exclamation attributes.
    boolean mIsEpithet = false;

    // Categories of words and phrases.
    boolean mIsAnimal = false;
    boolean mIsArchaic = false;
    boolean mIsBeingCapableOfLanguage = false;
    boolean mIsBodyPart = false;
    boolean mIsDerivative = false;
    boolean mIsRegional = false;
    boolean mIsFoodRelated = false;
    boolean mIsInvective = false;
    boolean mIsPlaceName = false;
    boolean mIsPrefix = false;
    boolean mIsSlang = false;
    boolean mIsSuffix = false;
    boolean mIsWeaponsRelated = false;

    // Additional metadata.
    boolean mIsAlternativeSpelling = false;
    boolean mIsFictionalEntity = false;
    boolean mIsHypothetical = false;
    boolean mIsExtendedCanon = false;
    boolean mDoNotLink = false;

    // For display purposes.
    boolean mIsIndented = false;

    // If there are multiple entries with identitical entry names,
    // they are distinguished with numbers. However, not all entries display
    // them, for various reasons.
    int mHomophoneNumber = -1;
    boolean mShowHomophoneNumber = true;

    // Link can be to an URL.
    String mURL = "";

    /**
     * Constructor
     *
     * <p>This creates an entry based only on the given query and definition. Note that the database
     * is NOT queried when this constructor is called. This constructor is used to create a fake
     * entry for the purpose of displaying KWOTD entries which are not in our database.
     *
     * @param query A query of the form "entryName:basepos:metadata".
     */
    public Entry(String query, String definition, Context context) {
      // Log.d(TAG, "Entry constructed from query: \"" + query + "\"");
      mEntryName = query;
      mContext = context;

      // Get analysis components, if any.
      int cmLoc = mEntryName.indexOf(COMPONENTS_MARKER);
      if (cmLoc != -1) {
        mComponents = mEntryName.substring(cmLoc + COMPONENTS_MARKER.length());
        mEntryName = mEntryName.substring(0, cmLoc);
      }

      // Get part of speech and attribute information.
      int colonLoc = mEntryName.indexOf(':');
      if (colonLoc != -1) {
        mPartOfSpeech = mEntryName.substring(colonLoc + 1);
        mEntryName = mEntryName.substring(0, colonLoc);
      }

      if (mDefinition != null) {
        mDefinition = definition;
      }

      // Note: The homophone number may be overwritten by this function call.
      processMetadata();
    }

    /**
     * Constructor
     *
     * <p>This creates an entry based on the given query. Note that the database is NOT queried when
     * this constructor is called. This constructor is used to create a fake placeholder entry for
     * the purpose of querying for a real entry from the database.
     */
    public Entry(String query, Context context) {
      this(query, null, context);
    }

    /**
     * Constructor
     *
     * <p>This creates an entry based on the given cursor, which is assumed to be the result of a
     * query to the database.
     *
     * @param cursor A cursor with position at the desired entry.
     */
    public Entry(Cursor cursor, Context context) {
      mContext = context;

      mId = cursor.getInt(KlingonContentDatabase.COLUMN_ID);
      mEntryName = cursor.getString(KlingonContentDatabase.COLUMN_ENTRY_NAME);
      mPartOfSpeech = cursor.getString(KlingonContentDatabase.COLUMN_PART_OF_SPEECH);

      // TODO: Make this dependent on the chosen language.
      mDefinition = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION);
      mNotes = cursor.getString(KlingonContentDatabase.COLUMN_NOTES);
      mSearchTags = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS);

      mDefinition_DE = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_DE);
      mNotes_DE = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_DE);
      mExamples_DE = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_DE);
      mSearchTags_DE = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_DE);

      mDefinition_FA = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_FA);
      mNotes_FA = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_FA);
      mExamples_FA = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_FA);
      mSearchTags_FA = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_FA);

      mDefinition_SV = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_SV);
      mNotes_SV = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_SV);
      mExamples_SV = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_SV);
      mSearchTags_SV = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_SV);

      mDefinition_RU = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_RU);
      mNotes_RU = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_RU);
      mExamples_RU = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_RU);
      mSearchTags_RU = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_RU);

      mDefinition_ZH_HK = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_ZH_HK);
      mNotes_ZH_HK = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_ZH_HK);
      mExamples_ZH_HK = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_ZH_HK);
      mSearchTags_ZH_HK = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_ZH_HK);

      mDefinition_PT = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_PT);
      mNotes_PT = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_PT);
      mExamples_PT = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_PT);
      mSearchTags_PT = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_PT);

      mDefinition_FI = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION_FI);
      mNotes_FI = cursor.getString(KlingonContentDatabase.COLUMN_NOTES_FI);
      mExamples_FI = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES_FI);
      mSearchTags_FI = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS_FI);

      mSynonyms = cursor.getString(KlingonContentDatabase.COLUMN_SYNONYMS);
      mAntonyms = cursor.getString(KlingonContentDatabase.COLUMN_ANTONYMS);
      mSeeAlso = cursor.getString(KlingonContentDatabase.COLUMN_SEE_ALSO);
      mHiddenNotes = cursor.getString(KlingonContentDatabase.COLUMN_HIDDEN_NOTES);
      mComponents = cursor.getString(KlingonContentDatabase.COLUMN_COMPONENTS);
      mExamples = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES);
      mSource = cursor.getString(KlingonContentDatabase.COLUMN_SOURCE);

      // The homophone number is -1 by default.
      // Note: The homophone number may be overwritten by this function call.
      processMetadata();
    }

    // Helper method to process metadata.
    private void processMetadata() {

      // Process metadata from part of speech.
      String base = mPartOfSpeech;
      String[] attributes = {};
      int colonLoc = mPartOfSpeech.indexOf(':');
      if (colonLoc != -1) {
        base = mPartOfSpeech.substring(0, colonLoc);
        attributes = mPartOfSpeech.substring(colonLoc + 1).split(",");
      }

      // First, find the base part of speech.
      mBasePartOfSpeech = BasePartOfSpeechEnum.UNKNOWN;
      if (base.equals("")) {
        // Do nothing if base part of speech is empty.
        // Log.w(TAG, "{" + mEntryName + "} has empty part of speech.");
      } else {
        for (int i = 0; i < basePartOfSpeechAbbreviations.length; i++) {
          if (base.equals(basePartOfSpeechAbbreviations[i])) {
            mBasePartOfSpeech = BasePartOfSpeechEnum.values()[i];
          }
        }
        if (mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN) {
          // Log warning if part of speech could not be determined.
          Log.w(
              TAG,
              "{" + mEntryName + "} has unrecognised part of speech: \"" + mPartOfSpeech + "\"");
        }
      }

      // Now, get other attributes from the part of speech metadata.
      for (String attr : attributes) {

        // Note prefixes and suffixes.
        if (attr.equals("pref")) {
          mIsPrefix = true;
        } else if (attr.equals("suff")) {
          mIsSuffix = true;
        } else if (attr.equals("indent")) {
          // This attribute is used internally to indent affixes which are attached to a word, and
          // to half-indent verbs with prefixes.
          mIsIndented = true;

          // Verb attributes.
        } else if (attr.equals("ambi")) {
          // All ambitransitive verbs are considered confirmed, since they are never marked as such
          // otherwise.
          mTransitivity = VerbTransitivityType.AMBITRANSITIVE;
          mTransitivityConfirmed = true;
        } else if (attr.equals("i")) {
          mTransitivity = VerbTransitivityType.INTRANSITIVE;
        } else if (attr.equals("i_c")) {
          mTransitivity = VerbTransitivityType.INTRANSITIVE;
          mTransitivityConfirmed = true;
        } else if (attr.equals("is")) {
          // All stative verbs are considered confirmed, since they are all of the form "to be [a
          // quality]". They behave like confirmed intransitive verbs in most cases, except in the
          // analysis of verbs with a type 5 noun suffix attached.
          mTransitivity = VerbTransitivityType.STATIVE;
          mTransitivityConfirmed = true;
        } else if (attr.equals("t")) {
          mTransitivity = VerbTransitivityType.TRANSITIVE;
        } else if (attr.equals("t_c")) {
          mTransitivity = VerbTransitivityType.TRANSITIVE;
          mTransitivityConfirmed = true;
        } else if (attr.equals("n5")) {
          // This is an attribute which does not appear in the database, but can be assigned to a
          // query to find only verbs which are attached to a type 5 noun suffix (verbs acting
          // adjectivally).
          mTransitivity = VerbTransitivityType.HAS_TYPE_5_NOUN_SUFFIX;

          // Noun attributes.
        } else if (attr.equals("name")) {
          mNounType = NounType.NAME;
          mShowHomophoneNumber = false;
        } else if (attr.equals("num")) {
          mNounType = NounType.NUMBER;
        } else if (attr.equals("pro")) {
          mNounType = NounType.PRONOUN;
        } else if (attr.equals("inhpl")) {
          mIsInherentPlural = true;
        } else if (attr.equals("inhps")) {
          mIsSingularFormOfInherentPlural = true;
        } else if (attr.equals("plural")) {
          mIsPlural = true;

          // Sentence attributes.
        } else if (attr.equals("eu")) {
          mSentenceType = SentenceType.EMPIRE_UNION_DAY;
        } else if (attr.equals("mv")) {
          mSentenceType = SentenceType.CURSE_WARFARE;
        } else if (attr.equals("idiom")) {
          mSentenceType = SentenceType.IDIOM;
        } else if (attr.equals("nt")) {
          mSentenceType = SentenceType.NENTAY;
        } else if (attr.equals("phr")) {
          mSentenceType = SentenceType.PHRASE;
        } else if (attr.equals("prov")) {
          mSentenceType = SentenceType.PROVERB;
        } else if (attr.equals("Ql")) {
          mSentenceType = SentenceType.MILITARY_CELEBRATION;
        } else if (attr.equals("rej")) {
          mSentenceType = SentenceType.REJECTION;
        } else if (attr.equals("rp")) {
          mSentenceType = SentenceType.REPLACEMENT_PROVERB;
        } else if (attr.equals("sp")) {
          mSentenceType = SentenceType.SECRECY_PROVERB;
        } else if (attr.equals("toast")) {
          mSentenceType = SentenceType.TOAST;
        } else if (attr.equals("lyr")) {
          mSentenceType = SentenceType.LYRICS;
        } else if (attr.equals("bc")) {
          mSentenceType = SentenceType.BEGINNERS_CONVERSATION;
        } else if (attr.equals("joke")) {
          mSentenceType = SentenceType.JOKE;

          // Exclamation attributes.
        } else if (attr.equals("epithet")) {
          mIsEpithet = true;
          // TODO: Determine whether epithets are treated as if they always implicitly refer to
          // beings capable of language, and if so, set mIsBeingCapableOfLanguage to true here.

          // Categories.
        } else if (attr.equals("anim")) {
          mIsAnimal = true;
        } else if (attr.equals("archaic")) {
          mIsArchaic = true;
        } else if (attr.equals("being")) {
          mIsBeingCapableOfLanguage = true;
        } else if (attr.equals("body")) {
          mIsBodyPart = true;
        } else if (attr.equals("deriv")) {
          mIsDerivative = true;
        } else if (attr.equals("reg")) {
          mIsRegional = true;
        } else if (attr.equals("food")) {
          mIsFoodRelated = true;
        } else if (attr.equals("inv")) {
          mIsInvective = true;
        } else if (attr.equals("place")) {
          mIsPlaceName = true;
        } else if (attr.equals("slang")) {
          mIsSlang = true;
        } else if (attr.equals("weap")) {
          mIsWeaponsRelated = true;

          // Additional metadata.
        } else if (attr.equals("alt")) {
          mIsAlternativeSpelling = true;
        } else if (attr.equals("fic")) {
          mIsFictionalEntity = true;
        } else if (attr.equals("hyp")) {
          mIsHypothetical = true;
        } else if (attr.equals("extcan")) {
          mIsExtendedCanon = true;
        } else if (attr.equals("nolink")) {
          mDoNotLink = true;
        } else if (attr.equals("noanki")) {
          // This is an attribute which does not appear in the database and is not used by this app.
          // It's used by the export_to_anki.py script to exclude entries which should be skipped
          // when generating an Anki deck.
        } else if (attr.equals("klcp1")) {
          // This is an attribute which does not appear in the database and is not used by this app.
          // It's used by the export_to_anki.py script to tag KLCP1 (Klingon Language Certification
          // Program level 1) vocabulary.

          // We have only a few homophonous entries.
        } else if (attr.equals("1")) {
          mHomophoneNumber = 1;
        } else if (attr.equals("2")) {
          mHomophoneNumber = 2;
        } else if (attr.equals("3")) {
          mHomophoneNumber = 3;
        } else if (attr.equals("4")) {
          mHomophoneNumber = 4;
        } else if (attr.equals("5")) {
          // Nothing should go as high as even 4.
          mHomophoneNumber = 5;
          // Same as above, but the number is hidden.
        } else if (attr.equals("1h")) {
          mHomophoneNumber = 1;
          mShowHomophoneNumber = false;
        } else if (attr.equals("2h")) {
          mHomophoneNumber = 2;
          mShowHomophoneNumber = false;
        } else if (attr.equals("3h")) {
          mHomophoneNumber = 3;
          mShowHomophoneNumber = false;
        } else if (attr.equals("4h")) {
          mHomophoneNumber = 4;
          mShowHomophoneNumber = false;
        } else if (attr.equals("5h")) {
          mHomophoneNumber = 5;
          mShowHomophoneNumber = false;

          // If this is an URL link, the attribute is the URL.
        } else if (isURL()) {
          mURL = attr;

          // No match to attributes.
        } else {
          // Log error if part of speech could not be determined.
          Log.e(TAG, "{" + mEntryName + "} has unrecognised attribute: \"" + attr + "\"");
        }
      }
    }

    // Get the _id of the entry.
    public int getId() {
      return mId;
    }

    private String maybeItalics(String s, boolean isHtml) {
      if (isHtml) {
        return "<i>" + s + "</i>";
      }
      return s;
    }

    // Get the name of the entry, optionally as an HTML string. Used in the entry title, share
    // intent text, and results lists.
    public String getFormattedEntryName(boolean isHtml) {
      // Note that an entry may have more than one of the archaic,
      // regional, or slang attributes.
      String attr = "";
      final String separator = mContext.getResources().getString(R.string.attribute_separator);
      if (mIsArchaic) {
        attr = maybeItalics(mContext.getResources().getString(R.string.attribute_archaic), isHtml);
      }
      if (mIsRegional) {
        if (!attr.equals("")) {
          attr += separator;
        }
        attr += maybeItalics(mContext.getResources().getString(R.string.attribute_regional), isHtml);
      }
      if (mIsSlang) {
        if (!attr.equals("")) {
          attr += separator;
        }
        attr += maybeItalics(mContext.getResources().getString(R.string.attribute_slang), isHtml);
      }
      // While whether an entry is a name isn't actually an attribute, treat it as one.
      if (isName()) {
        if (!attr.equals("")) {
          attr += separator;
        }
        attr += maybeItalics(mContext.getResources().getString(R.string.pos_name),
            isHtml);
      }
      if (!attr.equals("")) {
        if (isHtml) {
          // Should also set color to android:textColorSecondary.
          attr = " <small>(" + attr + ")</small>";
        } else {
          attr = " (" + attr + ")";
        }
      }

      // Mark hypothetical and extended canon entries with a "?".
      String formattedEntryName = mEntryName + attr;
      if (mIsHypothetical || mIsExtendedCanon) {
        if (isHtml) {
          formattedEntryName = "<sup><small>?</small></sup>" + formattedEntryName;
        } else {
          formattedEntryName = "?" + formattedEntryName;
        }
      }

      // Return name plus possible attributes.
      return formattedEntryName;
    }

    // Get the name of the entry written in {pIqaD} with its attributes.
    public SpannableStringBuilder getFormattedEntryNameInKlingonFont() {
      String entryName = getEntryNameInKlingonFont();
      SpannableStringBuilder ssb = new SpannableStringBuilder(entryName);
      Typeface klingonTypeface = KlingonAssistant.getKlingonFontTypeface(mContext);
      ssb.setSpan(new KlingonTypefaceSpan("", klingonTypeface), 0, entryName.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

      // TODO: Refactor and combine this with the logic in getFormattedEntryName().
      // TODO: Italicise the attributes.
      SpannableStringBuilder attr = new SpannableStringBuilder("");
      final String separator = mContext.getResources().getString(R.string.attribute_separator);
      final String archaic = mContext.getResources().getString(R.string.attribute_archaic);
      final String regional = mContext.getResources().getString(R.string.attribute_regional);
      final String slang = mContext.getResources().getString(R.string.attribute_slang);
      final String name = mContext.getResources().getString(R.string.pos_name);
      int start = 0;
      int end = 0;
      if (mIsArchaic) {
        end = start + archaic.length();
        attr.append(archaic);
        attr.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start,
            start + archaic.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = end;
      }
      if (mIsRegional) {
        if (!attr.toString().equals("")) {
          attr.append(separator);
          start += separator.length();
        }
        end = start + regional.length();
        attr.append(regional);
        attr.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, start +
            regional.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = end;
      }
      if (mIsSlang) {
        if (!attr.toString().equals("")) {
          attr.append(separator);
          start += separator.length();
        }
        end = start + slang.length();
        attr.append(slang);
        attr.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start,
            start + slang.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = end;
      }
      // While whether an entry is a name isn't actually an attribute, treat it as one.
      if (isName()) {
        if (!attr.toString().equals("")) {
          attr.append(separator);
          start += separator.length();
        }
        end = start + name.length();
        attr.append(name);
        attr.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start,
            start + name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = end;
      }
      if (!attr.toString().equals("")) {
        attr.append(")");
        attr = new SpannableStringBuilder(" (").append(attr);
        attr.setSpan(new RelativeSizeSpan(0.5f), 0, attr.toString().length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append(attr);
      }
      if (mIsHypothetical || mIsExtendedCanon) {
        ssb = new SpannableStringBuilder("?").append(ssb);
      }

      return ssb;
    }

    // Get the name of the entry written in {pIqaD}.
    public String getEntryNameInKlingonFont() {
      return KlingonContentProvider.convertStringToKlingonFont(mEntryName);
    }

    private String getSpecificPartOfSpeech() {
      String pos = basePartOfSpeechAbbreviations[mBasePartOfSpeech.ordinal()];
      if (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN) {
        if (mNounType == NounType.NUMBER) {
          pos =  mContext.getResources().getString(R.string.pos_number);
        } else if (mNounType == NounType.NAME) {
          pos =  mContext.getResources().getString(R.string.pos_name);
        } else if (mNounType == NounType.PRONOUN) {
          pos =  mContext.getResources().getString(R.string.pos_pronoun);
        } else {
          pos =  mContext.getResources().getString(R.string.pos_noun);
        }
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.VERB) {
        pos =  mContext.getResources().getString(R.string.pos_verb);
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.ADVERBIAL) {
        pos =  mContext.getResources().getString(R.string.pos_adv);
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.CONJUNCTION) {
        pos =  mContext.getResources().getString(R.string.pos_conj);
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.QUESTION) {
        pos =  mContext.getResources().getString(R.string.pos_ques);
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.EXCLAMATION) {
        pos =  mContext.getResources().getString(R.string.pos_excl);
      }
      return pos;
    }

    // This is called when creating the expanded definition in the entry, and also in
    // getFormattedDefinition below.
    public String getFormattedPartOfSpeech(boolean isHtml) {
      // Return abbreviation for part of speech, but suppress for sentences and names.
      String pos = "";
      if (isAlternativeSpelling()) {
        pos = mContext.getResources().getString(R.string.label_see_alt_entry) + ": ";
      } else if (mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE
          || mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME) {
        // Ignore part of speech for names and sentences.
        pos = "";
      } else {
        pos = getSpecificPartOfSpeech();
        if (isHtml) {
          pos = "<i>" + pos + ".</i> ";
        } else {
          pos = pos + ". ";
        }
      }
      return pos;
    }

    // Get the definition, including the part of speech. Called to create the sharing text for
    // the entry, and also the text in the search results list.
    public String getFormattedDefinition(boolean isHtml) {
      String pos = getFormattedPartOfSpeech(isHtml);

      // Get definition, and append other-language definition if appropriate.
      String definition = mDefinition;
      if (shouldDisplayOtherLanguageDefinition()) {
        // Display the other-language as the primary definition and the English as the secondary.
        definition = getOtherLanguageDefinition() + " / " + definition;
      }

      // Replace brackets in definition with bold.
      Matcher matcher = ENTRY_PATTERN.matcher(definition);
      while (matcher.find()) {
        // Strip brackets.
        String query = definition.substring(matcher.start() + 1, matcher.end() - 1);
        KlingonContentProvider.Entry linkedEntry =
            new KlingonContentProvider.Entry(query, mContext);
        String replacement;
        if (isHtml) {
          // Bold a Klingon word if there is one.
          replacement = "<b>" + linkedEntry.getEntryName() + "</b>";
        } else {
          // Just replace it with plain text.
          replacement = linkedEntry.getEntryName();
        }
        definition =
            definition.substring(0, matcher.start())
                + replacement
                + definition.substring(matcher.end());

        // Repeat.
        matcher = ENTRY_PATTERN.matcher(definition);
      }

      // Return the definition, preceded by the part of speech.
      return pos + definition;
    }

    public String getEntryName() {
      return mEntryName;
    }

    // Return the part of speech in brackets, but only for some cases. Called to display the
    // part of speech for linked entries in an entry, and also in the main results screen to
    // show what the original search term was.
    public String getBracketedPartOfSpeech(boolean isHtml) {
      // Return abbreviation for part of speech, but suppress for sentences, exclamations, etc.
      if (mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE
          || mBasePartOfSpeech == BasePartOfSpeechEnum.EXCLAMATION
          || mBasePartOfSpeech == BasePartOfSpeechEnum.SOURCE
          || mBasePartOfSpeech == BasePartOfSpeechEnum.URL
          || mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN
          || (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME)) {
        return "";
      }
      final String pos = getSpecificPartOfSpeech();

      final String defn =  mContext.getResources().getString(R.string.homophone_number);
      if (isHtml) {
        // This is used in the "results found" string.
        String bracketedPos = " <small>(<i>" + pos + "</i>)";
        if (mHomophoneNumber != -1 && mShowHomophoneNumber) {
          bracketedPos += " (" + defn + " " + mHomophoneNumber + ")";
        }
        bracketedPos += "</small>";
        return bracketedPos;
      } else {
        // This is used in an entry body next to linked entries.
        String bracketedPos = " (" + pos + ")";
        if (mHomophoneNumber != -1 && mShowHomophoneNumber) {
          bracketedPos += " (" + defn + " " + mHomophoneNumber + ")";
        }
        return bracketedPos;
      }
    }

    public String getPartOfSpeech() {
      return mPartOfSpeech;
    }

    public boolean basePartOfSpeechIsUnknown() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN;
    }

    public BasePartOfSpeechEnum getBasePartOfSpeech() {
      return mBasePartOfSpeech;
    }

    public String getDefinition() {
      return mDefinition;
    }

    // TODO: Refactor the additional languages code to be much more compact.
    // These functions should probably take a language code as a second parameter.
    public String getOtherLanguageDefinition() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */ "NONE");
      switch (otherLang) {
        case "de":
          return getDefinition_DE();
        case "fa":
          return getDefinition_FA();
        case "ru":
          return getDefinition_RU();
        case "sv":
          return getDefinition_SV();
        case "zh-HK":
          return getDefinition_ZH_HK();
        case "pt":
          return getDefinition_PT();
        case "fi":
          return getDefinition_FI();
        default:
          // All definitions should exist (even if they are autotranslated), so this should never
          // be reached, but in case it is, return the English definition by default.
          return getDefinition();
      }
    }

    public String getDefinition_DE() {
      // If there is no German definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_DE == null) ? "" : mDefinition_DE;
    }

    public String getNotes_DE() {
      // If there are no German notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_DE == null) ? "" : mNotes_DE;
    }

    public String getExamples_DE() {
      // If there are no German examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_DE == null) ? "" : mExamples_DE;
    }

    public String getSearchTags_DE() {
      // If there are no German search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_DE == null) ? "" : mSearchTags_DE;
    }

    // Returns true iff the other-language definition should displayed.
    public boolean shouldDisplayOtherLanguageDefinition() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */
              Preferences.getSystemPreferredLanguage());
      if (otherLang != "NONE") {
        // Show other-language definitions preference set to a language and that other-language
        // definition is not empty or identical to the English.
        String otherLanguageDefinition = getOtherLanguageDefinition();
        return otherLanguageDefinition != null
            && !otherLanguageDefinition.equals("")
            && !otherLanguageDefinition.equals(mDefinition);
      } else {
        return false;
      }
    }

    // Returns true iff the other-language notes should be displayed. Note that the other-language
    // notes can be set to the string "-" (meaning the other-language notes are empty, but these
    // empty notes override the English notes), in which case this function will still return true.
    // It's up to the caller to "display" these empty notes (i.e., suppress the English notes).
    public boolean shouldDisplayOtherLanguageNotes() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */
              Preferences.getSystemPreferredLanguage());
      if (otherLang != "NONE") {
        // Show other-language definitions preference set to a language and that other-language
        // notes are not empty or identical to the English.
        String otherLanguageNotes = getOtherLanguageNotes();
        return otherLanguageNotes != null
            && !otherLanguageNotes.equals("")
            && !otherLanguageNotes.equals(mNotes);
      } else {
        return false;
      }
    }

    // Returns true iff the other-language examples should be displayed.
    public boolean shouldDisplayOtherLanguageExamples() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */
              Preferences.getSystemPreferredLanguage());
      if (otherLang != "NONE") {
        // Show other-language definitions preference set to a language and that other-language
        // examples are not empty.
        String otherLanguageExamples = getOtherLanguageExamples();
        return otherLanguageExamples != null && !otherLanguageExamples.equals("");
      } else {
        return false;
      }
    }

    public String getDefinition_FA() {
      // If there is no Persian definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_FA == null) ? "" : mDefinition_FA;
    }

    public String getNotes_FA() {
      // If there are no Persian notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_FA == null) ? "" : mNotes_FA;
    }

    public String getExamples_FA() {
      // If there are no Persian examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_FA == null) ? "" : mExamples_FA;
    }

    public String getSearchTags_FA() {
      // If there are no Persian search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_FA == null) ? "" : mSearchTags_FA;
    }

    public String getDefinition_SV() {
      // If there is no Swedish definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_SV == null) ? "" : mDefinition_SV;
    }

    public String getNotes_SV() {
      // If there are no Swedish notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_SV == null) ? "" : mNotes_SV;
    }

    public String getExamples_SV() {
      // If there are no Swedish examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_SV == null) ? "" : mExamples_SV;
    }

    public String getSearchTags_SV() {
      // If there are no Swedish search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_SV == null) ? "" : mSearchTags_SV;
    }

    public String getDefinition_RU() {
      // If there is no Russian definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_RU == null) ? "" : mDefinition_RU;
    }

    public String getNotes_RU() {
      // If there are no Russian notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_RU == null) ? "" : mNotes_RU;
    }

    public String getExamples_RU() {
      // If there are no Russian examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_RU == null) ? "" : mExamples_RU;
    }

    public String getSearchTags_RU() {
      // If there are no Russian search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_RU == null) ? "" : mSearchTags_RU;
    }

    public String getDefinition_ZH_HK() {
      // If there is no Chinese (Hong Kong) definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_ZH_HK == null) ? "" : mDefinition_ZH_HK;
    }

    public String getNotes_ZH_HK() {
      // If there are no Chinese (Hong Kong) notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_ZH_HK == null) ? "" : mNotes_ZH_HK;
    }

    public String getExamples_ZH_HK() {
      // If there are no Chinese (Hong Kong) examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_ZH_HK == null) ? "" : mExamples_ZH_HK;
    }

    public String getSearchTags_ZH_HK() {
      // If there are no Chinese (Hong Kong) search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_ZH_HK == null) ? "" : mSearchTags_ZH_HK;
    }

    public String getDefinition_PT() {
      // If there is no Portuguese definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_PT == null) ? "" : mDefinition_PT;
    }

    public String getNotes_PT() {
      // If there are no Portuguese notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_PT == null) ? "" : mNotes_PT;
    }

    public String getExamples_PT() {
      // If there are no Portuguese examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_PT == null) ? "" : mExamples_PT;
    }

    public String getSearchTags_PT() {
      // If there are no Portuguese search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_PT == null) ? "" : mSearchTags_PT;
    }

    public String getDefinition_FI() {
      // If there is no Finnish definition, the cursor could've returned
      // null, so that needs to be handled.
      return (mDefinition_FI == null) ? "" : mDefinition_FI;
    }

    public String getNotes_FI() {
      // If there are no Finnish notes, the cursor could've returned
      // null, so that needs to be handled.
      return (mNotes_FI == null) ? "" : mNotes_FI;
    }

    public String getExamples_FI() {
      // If there are no Finnish examples, the cursor could've returned
      // null, so that needs to be handled.
      return (mExamples_FI == null) ? "" : mExamples_FI;
    }

    public String getSearchTags_FI() {
      // If there are no Finnish search tags, the cursor could've returned
      // null, so that needs to be handled.
      return (mSearchTags_FI == null) ? "" : mSearchTags_FI;
    }

    public String getSynonyms() {
      return mSynonyms;
    }

    public String getAntonyms() {
      return mAntonyms;
    }

    public String getSeeAlso() {
      return mSeeAlso;
    }

    public String getNotes() {
      return mNotes;
    }

    // TODO: Refactor.
    public String getOtherLanguageNotes() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */ "NONE");
      switch (otherLang) {
        case "de":
          return getNotes_DE();
        case "fa":
          return getNotes_FA();
        case "ru":
          return getNotes_RU();
        case "sv":
          return getNotes_SV();
        case "zh-HK":
          return getNotes_ZH_HK();
        case "pt":
          return getNotes_PT();
        case "fi":
          return getNotes_FI();
        default:
          // By default, return the English notes if other-language notes don't exist.
          return getNotes();
      }
    }

    public String getHiddenNotes() {
      return mHiddenNotes;
    }

    public String getComponents() {
      return mComponents;
    }

    public ArrayList<Entry> getComponentsAsEntries() {
      ArrayList<Entry> componentEntriesList = new ArrayList<Entry>();
      if (mComponents.trim().equals("")) {
        // Components is empty, return empty list.
        return componentEntriesList;
      }
      // There must be exactly one space after the comma, since comma-space separates entries while
      // comma by itself separates attributes of an entry.
      String[] componentQueries = mComponents.split("\\s*, \\s*");
      for (int i = 0; i < componentQueries.length; i++) {
        String componentQuery = componentQueries[i];
        Entry componentEntry = new Entry(componentQuery, mContext);
        componentEntriesList.add(componentEntry);
      }
      return componentEntriesList;
    }

    public String getExamples() {
      return mExamples;
    }

    // TODO: Refactor.
    public String getOtherLanguageExamples() {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
      final String otherLang =
          sharedPrefs.getString(
              Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */ "NONE");
      switch (otherLang) {
        case "de":
          return getExamples_DE();
        case "fa":
          return getExamples_FA();
        case "ru":
          return getExamples_RU();
        case "sv":
          return getExamples_SV();
        case "zh-HK":
          return getExamples_ZH_HK();
        case "pt":
          return getExamples_PT();
        case "fi":
          return getExamples_FI();
        default:
          return getExamples();
      }
    }

    public String getSearchTags() {
      return mSearchTags;
    }

    public String getSource() {
      return mSource;
    }

    public int getHomophoneNumber() {
      return mHomophoneNumber;
    }

    public boolean isInherentPlural() {
      return mIsInherentPlural;
    }

    public boolean isSingularFormOfInherentPlural() {
      return mIsSingularFormOfInherentPlural;
    }

    public boolean isPlural() {
      // This noun is already plural (e.g., the entry already has plural suffixes).
      // This is different from an inherent plural, which acts like a singular object
      // for the purposes of verb agreement.
      return mIsPlural;
    }

    public boolean isEpithet() {
      return mIsEpithet;
    }

    public boolean isArchaic() {
      return mIsArchaic;
    }

    public boolean isBeingCapableOfLanguage() {
      return mIsBeingCapableOfLanguage;
    }

    public boolean isBodyPart() {
      return mIsBodyPart;
    }

    public boolean isDerivative() {
      return mIsDerivative;
    }

    public boolean isRegional() {
      return mIsRegional;
    }

    public boolean isFoodRelated() {
      return mIsFoodRelated;
    }

    public boolean isPlaceName() {
      return mIsPlaceName;
    }

    public boolean isInvective() {
      return mIsInvective;
    }

    public boolean isSlang() {
      return mIsSlang;
    }

    public boolean isWeaponsRelated() {
      return mIsWeaponsRelated;
    }

    public boolean isAlternativeSpelling() {
      return mIsAlternativeSpelling;
    }

    public boolean isFictionalEntity() {
      return mIsFictionalEntity;
    }

    public boolean isHypothetical() {
      return mIsHypothetical;
    }

    public boolean isExtendedCanon() {
      return mIsExtendedCanon;
    }

    public boolean isSource() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.SOURCE;
    }

    public boolean isURL() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.URL;
    }

    public String getURL() {
      // If this is a source (like "TKD", "KGT", etc.), try to derive the URL from the entry name.
      final Pattern TKD_PAGE_PATTERN = Pattern.compile("TKD p.([0-9]+)");
      final Pattern TKD_SECTION_PATTERN = Pattern.compile("TKD ([0-9]\\.[0-9](?:\\.[0-9])?)");
      final Pattern TKDA_SECTION_PATTERN = Pattern.compile("TKDA ([0-9]\\.[0-9](?:\\.[0-9])?)");
      final Pattern KGT_PAGE_PATTERN = Pattern.compile("KGT p.([0-9]+)");
      if (isSource()) {
        Matcher m;

        // Check TKD page number.
        m = TKD_PAGE_PATTERN.matcher(mEntryName);
        if (m.find()) {
          // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
          String URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC";
          URL += "&pg=GBS.PA" + Integer.parseInt(m.group(1));
          return URL;
        }

        // Check TKD section number.
        m = TKD_SECTION_PATTERN.matcher(mEntryName);
        if (m.find()) {
          // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
          String URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC";
          String section = m.group(1);
          // TODO: Refactor this into something nicer.
          if (section != null) {
            if (section.equals("3.2.1") || section.equals("3.2.2")) {
              URL += "&pg=GBS.PA19";
            } else if (section.equals("3.2.3")) {
              URL += "&pg=GBS.PA20";
            } else if (section.equals("3.3.1") || section.equals("3.3.2")) {
              URL += "&pg=GBS.PA21";
            } else if (section.equals("3.3.3")) {
              URL += "&pg=GBS.PA24";
            } else if (section.equals("3.3.4")) {
              URL += "&pg=GBS.PA25";
            } else if (section.equals("3.3.5")) {
              URL += "&pg=GBS.PA26";
            } else if (section.equals("3.3.6")) {
              URL += "&pg=GBS.PA29";
            } else if (section.equals("3.4")) {
              URL += "&pg=GBS.PA30";
            } else if (section.equals("4.2.1")) {
              URL += "&pg=GBS.PA35";
            } else if (section.equals("4.2.2")) {
              URL += "&pg=GBS.PA36";
            } else if (section.equals("4.2.3")) {
              URL += "&pg=GBS.PA37";
            } else if (section.equals("4.2.4") || section.equals("4.2.5")) {
              URL += "&pg=GBS.PA38";
            } else if (section.equals("4.2.6")) {
              URL += "&pg=GBS.PA39";
            } else if (section.equals("4.2.7")) {
              URL += "&pg=GBS.PA40";
            } else if (section.equals("4.2.8") || section.equals("4.2.9")) {
              URL += "&pg=GBS.PA43";
            } else if (section.equals("4.2.10")) {
              URL += "&pg=GBS.PA44";
            } else if (section.equals("4.3")) {
              URL += "&pg=GBS.PA46";
            } else if (section.equals("4.4")) {
              URL += "&pg=GBS.PA49";
            } else if (section.equals("5.1")) {
              URL += "&pg=GBS.PA51";
            } else if (section.equals("5.2")) {
              URL += "&pg=GBS.PA52";
            } else if (section.equals("5.3") || section.equals("5.4")) {
              URL += "&pg=GBS.PA55";
            } else if (section.equals("5.5")) {
              URL += "&pg=GBS.PA57";
            } else if (section.equals("5.6")) {
              URL += "&pg=GBS.PA58";
            } else if (section.equals("6.1")) {
              URL += "&pg=GBS.PA59";
            } else if (section.equals("6.2.1")) {
              URL += "&pg=GBS.PA61";
            } else if (section.equals("6.2.2")) {
              URL += "&pg=GBS.PA62";
            } else if (section.equals("6.2.3")) {
              URL += "&pg=GBS.PA63";
            } else if (section.equals("6.2.4")) {
              URL += "&pg=GBS.PA64";
            } else if (section.equals("6.2.5")) {
              URL += "&pg=GBS.PA65";
            } else if (section.equals("6.3")) {
              URL += "&pg=GBS.PA67";
            } else if (section.equals("6.4")) {
              URL += "&pg=GBS.PA68";
            } else if (section.equals("6.5") || section.equals("6.6")) {
              URL += "&pg=GBS.PA70";
            }
          }
          return URL;
        }

        // Check TKDA section number.
        m = TKDA_SECTION_PATTERN.matcher(mEntryName);
        if (m.find()) {
          // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
          String URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC";
          String section = m.group(1);
          // TODO: Refactor this into something nicer.
          if (section != null) {
            if (section.equals("3.3.1")) {
              URL += "&pg=GBS.PA174";
            } else if (section.equals("4.2.6") || section.equals("4.2.9")) {
              URL += "&pg=GBS.PA175";
            } else if (section.equals("6.7") || section.equals("6.8")) {
              URL += "&pg=GBS.PA179";
            }
          }
          return URL;
        }

        // For whatever reason, TKW is not found in Google Play Books.

        // Check KGT.
        m = KGT_PAGE_PATTERN.matcher(mEntryName);
        if (m.find()) {
          // There is a second identical copy of the book at ID "9Vz1q4p87GgC".
          String URL = "https://play.google.com/books/reader?id=B5AiSVBw7nMC";
          if (m.group(1) != null) {
            // The page numbers in the Google Play Books version of KGT is offset by about 9 pages
            // from the physical edition of the book, so adjust for that. There is allegedly another
            // parameter "PA" which allows linking to the printed page number. But apparently this
            // doesn't work for this book.
            int pageNumber = Integer.parseInt(m.group(1)) + 9;
            // The "PA" parameter appears not to work on this book.
            URL += "&pg=GBS.PT" + pageNumber;
          }
          return URL;
        }
      }

      // Otherwise, return the entry's URL (which will only be non-empty if this is an URL).
      return mURL;
    }

    public boolean doNotLink() {
      return mDoNotLink;
    }

    public boolean isIndented() {
      return mIsIndented;
    }

    public boolean isPronoun() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.PRONOUN;
    }

    public boolean isName() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME;
    }

    public boolean isNumber() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NUMBER;
    }

    public boolean isSentence() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE;
    }

    public String getSentenceType() {
      if (mSentenceType == SentenceType.EMPIRE_UNION_DAY) {
        return mContext.getResources().getString(R.string.empire_union_day);
      } else if (mSentenceType == SentenceType.CURSE_WARFARE) {
        return mContext.getResources().getString(R.string.curse_warfare);
        /*
         * IDIOM is commented out because it's not in the menu yet, since we have no good
         * translation for the word. } else if (mSentenceType == SentenceType.IDIOM) {
         * return mContext.getResources().getString(R.string.idioms); }
         */
      } else if (mSentenceType == SentenceType.NENTAY) {
        return mContext.getResources().getString(R.string.nentay);
        /*
         * PROVERB is also commented out because it's not in the menu yet either, due to
         * incompleteness. } else if (mSentenceType == SentenceType.PROVERB) { return
         * mContext.getResources().getString(R.string.proverbs); }
         */
      } else if (mSentenceType == SentenceType.MILITARY_CELEBRATION) {
        return mContext.getResources().getString(R.string.military_celebration);
      } else if (mSentenceType == SentenceType.REJECTION) {
        return mContext.getResources().getString(R.string.rejection);
      } else if (mSentenceType == SentenceType.REPLACEMENT_PROVERB) {
        return mContext.getResources().getString(R.string.replacement_proverbs);
      } else if (mSentenceType == SentenceType.SECRECY_PROVERB) {
        return mContext.getResources().getString(R.string.secrecy_proverbs);
      } else if (mSentenceType == SentenceType.TOAST) {
        return mContext.getResources().getString(R.string.toasts);
      } else if (mSentenceType == SentenceType.LYRICS) {
        return mContext.getResources().getString(R.string.lyrics);
      } else if (mSentenceType == SentenceType.BEGINNERS_CONVERSATION) {
        return mContext.getResources().getString(R.string.beginners_conversation);
      } else if (mSentenceType == SentenceType.JOKE) {
        return mContext.getResources().getString(R.string.jokes);
      }

      // The empty string is returned if the type is general PHRASE.
      return "";
    }

    public String getSentenceTypeQuery() {
      // TODO: Refactor this to use existing constants.
      if (mSentenceType == SentenceType.EMPIRE_UNION_DAY) {
        return "*:sen:eu";
      } else if (mSentenceType == SentenceType.CURSE_WARFARE) {
        return "*:sen:mv";
      } else if (mSentenceType == SentenceType.IDIOM) {
        return "*:sen:idiom";
      } else if (mSentenceType == SentenceType.NENTAY) {
        return "*:sen:nt";
      } else if (mSentenceType == SentenceType.PROVERB) {
        return "*:sen:prov";
      } else if (mSentenceType == SentenceType.MILITARY_CELEBRATION) {
        return "*:sen:Ql";
      } else if (mSentenceType == SentenceType.REJECTION) {
        return "*:sen:rej";
      } else if (mSentenceType == SentenceType.REPLACEMENT_PROVERB) {
        return "*:sen:rp";
      } else if (mSentenceType == SentenceType.SECRECY_PROVERB) {
        return "*:sen:sp";
      } else if (mSentenceType == SentenceType.TOAST) {
        return "*:sen:toast";
      } else if (mSentenceType == SentenceType.LYRICS) {
        return "*:sen:lyr";
      } else if (mSentenceType == SentenceType.BEGINNERS_CONVERSATION) {
        return "*:sen:bc";
      } else if (mSentenceType == SentenceType.JOKE) {
        return "*:sen:joke";
      }

      // A general phrase. In theory this should never be returned.
      return "*:sen:phr";
    }

    // This is a verb (but not a prefix or suffix).
    public boolean isVerb() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.VERB && !isPrefix() && !isSuffix();
    }

    public boolean isPrefix() {
      // It's necessary to check that the entry name ends with "-" because links (e.g., the list of
      // components) are not fully annotated.
      return mBasePartOfSpeech == BasePartOfSpeechEnum.VERB
          && (mIsPrefix || mEntryName.endsWith("-"));
    }

    public boolean isSuffix() {
      // It's necessary to check that the entry name starts with "-" because links (e.g., the list
      // of components) are not fully annotated.
      return mIsSuffix || mEntryName.startsWith("-");
    }

    // This is a noun (including possible a noun suffix).
    // TODO: Make this symmetric with isVerb().
    // Test case: {bISutlhnISchugh, jaghlI' minDu' tIbej} should evaluate {-lI'} to a noun suffix.
    public boolean isNoun() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN;
    }

    // {chuvmey} - not sentences, but not verbs/nouns/affixes either.
    public boolean isMisc() {
      return mBasePartOfSpeech == BasePartOfSpeechEnum.ADVERBIAL
          || mBasePartOfSpeech == BasePartOfSpeechEnum.CONJUNCTION
          || mBasePartOfSpeech == BasePartOfSpeechEnum.QUESTION;
    }

    public int getTextColor() {
      // TODO: Make the colours customisable. For now, use Lieven's system.
      // https://code.google.com/p/klingon-assistant/issues/detail?id=8
      if (isHypothetical() || isExtendedCanon()) {
        return Color.GRAY;
      } else if (isVerb()) {
        return Color.YELLOW;
      } else if (isNoun() && !isSuffix() && !isNumber() && !isPronoun()) {
        // Note: isNoun() also returns true if it's a suffix, but isVerb() returns false.
        // TODO: fix this asymmetry between isNoun() and isVerb().
        return Color.GREEN;
      } else if (isSuffix() || isPrefix()) {
        return Color.RED;
      } else if (isMisc() || isNumber() || isPronoun()) {
        return Color.CYAN;
      }
      return Color.WHITE;
    }

    public VerbTransitivityType getTransitivity() {
      return mTransitivity;
    }

    public String getTransitivityString() {
      switch (mTransitivity) {
        case AMBITRANSITIVE:
          return mContext.getResources().getString(R.string.transitivity_ambi);

        case INTRANSITIVE:
          if (mTransitivityConfirmed) {
            return mContext.getResources().getString(R.string.transitivity_intransitive_confirmed);
          } else {
            return mContext.getResources().getString(R.string.transitivity_intransitive);
          }

        case STATIVE:
          return mContext.getResources().getString(R.string.transitivity_stative);

        case TRANSITIVE:
          if (mTransitivityConfirmed) {
            return mContext.getResources().getString(R.string.transitivity_transitive_confirmed);
          } else {
            return mContext.getResources().getString(R.string.transitivity_transitive);
          }

        default:
          // This is reached if the verb transitivity type is unknown, or if for some reason this
          // function is called on a verb with a type 5 noun suffix attached, which shouldn't
          // happen.
          return mContext.getResources().getString(R.string.transitivity_unknown);
      }
    }

    // Called on a query entry, determines if the query is satisfied by the candidate entry.
    public boolean isSatisfiedBy(Entry candidate) {
      Log.d(TAG, "\nisSatisfiedBy candidate: " + candidate.getEntryName());

      // Determine whether entry name matches exactly.
      boolean isExactMatchForEntryName = mEntryName.equals(candidate.getEntryName());

      // If the part of speech is unknown, be much less strict, because
      // the query was typed from the search box.
      if (!basePartOfSpeechIsUnknown()) {
        // Base part of speech is known, so match exact entry name as
        // well as base part of speech.
        Log.d(TAG, "isExactMatchForEntryName: " + isExactMatchForEntryName);
        if (!isExactMatchForEntryName) {
          return false;
        }
        // The parts of speech must match, except when: we're looking for a verb, in which
        // case a pronoun will satisfy the requirement; or we're looking for a noun, in which case
        // the question words {nuq} and {'Iv}, as well as exclamations which are epithets, are
        // accepted. We have these exceptions because we want to allow constructions like {ghaHtaH},
        // for those two question words to take the place of the nouns they are asking about, and
        // to parse constructions such as {petaQpu'}. Note that entries knows nothing about affixes,
        // so it's up to the caller to exclude, e.g., prefixes on pronouns. The homophony of
        // {'Iv:ques} and {'Iv:n} necessitates adding a homophone number (in the database) to
        // distinguish them.
        // TODO: Remove redundant {nuq} + {-Daq}.
        Log.d(TAG, "mBasePartOfSpeech: " + mBasePartOfSpeech);
        Log.d(TAG, "candidate.getBasePartOfSpeech: " + candidate.getBasePartOfSpeech());
        Log.d(TAG, "candidate.getEntryName: " + candidate.getEntryName());
        boolean candidateIsPronounActingAsVerb =
            (mBasePartOfSpeech == BasePartOfSpeechEnum.VERB && candidate.isPronoun());
        boolean candidateIsQuestionWordActingAsNoun =
            (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN
                && (candidate.getEntryName().equals("nuq")
                    || candidate.getEntryName().equals("'Iv")));
        boolean candidateIsExclamationActingAsNoun =
            (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && candidate.isEpithet());
        if (mBasePartOfSpeech != candidate.getBasePartOfSpeech()) {
          if (!candidateIsPronounActingAsVerb
              && !candidateIsQuestionWordActingAsNoun
              && !candidateIsExclamationActingAsNoun) {
            return false;
          }
        }
        // However, if we're looking for a verb with a type 5 noun suffix attached, then we disallow
        // transitive verbs as well as pronouns. We also disallow (confirmed) intransitive verbs.
        // Note that pronouns with a type 5 noun suffix are already covered under nouns, so if we
        // allowed it here they would be duplicated. Also, even though only adjectival verbs can
        // take a type 5 noun suffix, we allow not only stative verbs (like {tIn}) and
        // ambitransitive verbs (like {pegh}), but also unconfirmed intransitive verbs, since it's
        // possible they've been misclassified and are actually stative.
        // (So this should exclude the erroneous analysis for {lervaD:n} as {ler:v} + {-vaD:n},
        // since {ler:v} is confirmed intransitive.)
        if (mBasePartOfSpeech == BasePartOfSpeechEnum.VERB
            && mTransitivity == VerbTransitivityType.HAS_TYPE_5_NOUN_SUFFIX
            && (candidate.isPronoun()
                || candidate.getTransitivity() == VerbTransitivityType.TRANSITIVE
                || (candidate.getTransitivity() == VerbTransitivityType.INTRANSITIVE &&
                    candidate.mTransitivityConfirmed))) {
          return false;
        }
      }
      Log.d(TAG, "Exact name match for entry name: " + candidate.getEntryName());
      Log.d(TAG, "Part of speech satisfied for: " + candidate.getEntryName());

      // If the homophone number is given, it must match.
      if (mHomophoneNumber != -1 && mHomophoneNumber != candidate.getHomophoneNumber()) {
        return false;
      }

      // If search for an attribute, candidate must have it.
      if (mIsSlang && !candidate.isSlang()) {
        return false;
      }
      if (mIsRegional && !candidate.isRegional()) {
        return false;
      }
      if (mIsArchaic && !candidate.isArchaic()) {
        return false;
      }
      if (isName() && !candidate.isName()) {
        return false;
      }
      if (isNumber() && !candidate.isNumber()) {
        return false;
      }

      // Treat fictional differently?
      // if (mIsFictional && !candidate.isFictional()) {
      // return false;
      // }

      // TODO: Test a bunch of other things here.
      Log.d(
          TAG,
          "Candidate passed: "
              + candidate.getEntryName()
              + candidate.getBracketedPartOfSpeech(/* html */ false));
      return true;
    }
  }

  // This class is for complex Klingon words. A complex word is a noun or verb with affixes.
  // Note: To debug parsing, you likely want to use "adb logcat -s
  // KlingonContentProvider.ComplexWord"
  // or "adb logcat -s KlingonContentProvider.ComplexWord KlingonContentProvider".
  public static class ComplexWord {
    // The logging tag can be at most 23 characters. "KlingonContentProvider.ComplexWord" was too
    // long.
    String TAG = "KCP.ComplexWord";

    // The noun suffixes.
    static String[] nounType1String = {"", "'a'", "Hom", "oy"};
    static String[] nounType2String = {"", "pu'", "Du'", "mey"};
    static String[] nounType3String = {"", "qoq", "Hey", "na'"};
    static String[] nounType4String = {
      "", "wIj", "wI'", "maj", "ma'", "lIj", "lI'", "raj", "ra'", "Daj", "chaj", "vam", "vetlh"
    };
    static String[] nounType5String = {"", "Daq", "vo'", "mo'", "vaD", "'e'"};
    static String[][] nounSuffixesStrings = {
      nounType1String, nounType2String, nounType3String, nounType4String, nounType5String
    };
    int mNounSuffixes[] = new int[nounSuffixesStrings.length];

    // The verb prefixes.
    static String[] verbPrefixString = {
      "", "bI", "bo", "che", "cho", "Da", "DI", "Du", "gho", "HI", "jI", "ju", "lI", "lu", "ma",
      "mu", "nI", "nu", "pe", "pI", "qa", "re", "Sa", "Su", "tI", "tu", "vI", "wI", "yI"
    };
    static String[] verbTypeRUndoString = {
      // {-Ha'} always occurs immediately after
      // the verb.
      "", "Ha'"
    };
    static String[] verbType1String = {"", "'egh", "chuq"};
    static String[] verbType2String = {"", "nIS", "qang", "rup", "beH", "vIp"};
    static String[] verbType3String = {"", "choH", "qa'"};
    static String[] verbType4String = {"", "moH"};
    static String[] verbType5String = {"", "lu'", "laH", "luH", "la'"};
    static String[] verbType6String = {"", "chu'", "bej", "ba'", "law'"};
    static String[] verbType7String = {"", "pu'", "ta'", "taH", "lI'"};
    static String[] verbType8String = {"", "neS"};
    static String[] verbTypeRRefusal = {
      // {-Qo'} always occurs last, unless
      // followed by a type 9 suffix.
      "", "Qo'"
    };
    static String[] verbType9String = {
      "", "DI'", "chugh", "pa'", "vIS", "mo'", "bogh", "meH", "'a'", "jaj", "wI'", "ghach"
    };
    static String[][] verbSuffixesStrings = {
      verbTypeRUndoString,
      verbType1String,
      verbType2String,
      verbType3String,
      verbType4String,
      verbType5String,
      verbType6String,
      verbType7String,
      verbType8String,
      verbTypeRRefusal,
      verbType9String
    };
    int mVerbPrefix;
    int mVerbSuffixes[] = new int[verbSuffixesStrings.length];

    static String[] numberDigitString = {
      // {pagh} is excluded because it should
      // normally not form part of a number with
      // modifiers.
      "", "wa'", "cha'", "wej", "loS", "vagh", "jav", "Soch", "chorgh", "Hut"
    };
    static String[] numberModifierString = {
      "", "maH", "vatlh", "SaD", "SanID", "netlh", "bIp", "'uy'", "Saghan"
    };
    int mNumberDigit;
    int mNumberModifier;
    String mNumberSuffix;
    boolean mIsNumberLike;

    // The locations of the true rovers. The value indicates the suffix type they appear after,
    // so 0 means they are attached directly to the verb (before any type 1 suffix).
    int mVerbTypeRNegation;
    int mVerbTypeREmphatic;
    private static final int ROVER_NOT_YET_FOUND = -1;
    private static final int IGNORE_THIS_ROVER = -2;

    // True if {-be'} appears before {-qu'} in a verb.
    boolean roverOrderNegationBeforeEmphatic;

    // Internal information related to processing the complex word candidate.
    // TODO: There are few complex words which are neither nouns nor verbs, e.g., {batlhHa'},
    // {paghlogh}, {HochDIch}. Figure out how to deal with them.
    String mUnparsedPart;
    int mSuffixLevel;
    boolean mIsNounCandidate;
    boolean mIsVerbWithType5NounSuffix;
    int mHomophoneNumber;

    /**
     * Constructor
     *
     * @param candidate A potential candidate for a complex word.
     * @param isNounCandidate Set to true if noun, false if verb.
     */
    public ComplexWord(String candidate, boolean isNounCandidate) {
      mUnparsedPart = candidate;
      mIsNounCandidate = isNounCandidate;
      mIsVerbWithType5NounSuffix = false;
      mHomophoneNumber = -1;

      if (mIsNounCandidate) {
        // Five types of noun suffixes.
        mSuffixLevel = nounSuffixesStrings.length;
      } else {
        // Nine types of verb suffixes.
        mSuffixLevel = verbSuffixesStrings.length;
      }

      for (int i = 0; i < mNounSuffixes.length; i++) {
        mNounSuffixes[i] = 0;
      }

      mVerbPrefix = 0;
      for (int i = 0; i < mVerbSuffixes.length; i++) {
        mVerbSuffixes[i] = 0;
      }

      // Rovers.
      mVerbTypeRNegation = ROVER_NOT_YET_FOUND;
      mVerbTypeREmphatic = ROVER_NOT_YET_FOUND;
      roverOrderNegationBeforeEmphatic = false;

      // Number parts.
      mNumberDigit = 0;
      mNumberModifier = 0;
      mNumberSuffix = "";
      mIsNumberLike = false;
    }

    /**
     * Copy constructor
     *
     * @param unparsedPart The unparsedPart of this complex word.
     * @param complexWordToCopy
     */
    public ComplexWord(String unparsedPart, ComplexWord complexWordToCopy) {
      mUnparsedPart = unparsedPart;
      mIsNounCandidate = complexWordToCopy.mIsNounCandidate;
      mIsVerbWithType5NounSuffix = complexWordToCopy.mIsVerbWithType5NounSuffix;
      mHomophoneNumber = complexWordToCopy.mHomophoneNumber;
      mSuffixLevel = complexWordToCopy.mSuffixLevel;
      mVerbPrefix = complexWordToCopy.mVerbPrefix;
      for (int i = 0; i < mNounSuffixes.length; i++) {
        mNounSuffixes[i] = complexWordToCopy.mNounSuffixes[i];
      }
      for (int j = 0; j < mVerbSuffixes.length; j++) {
        mVerbSuffixes[j] = complexWordToCopy.mVerbSuffixes[j];
      }
      mVerbTypeRNegation = complexWordToCopy.mVerbTypeRNegation;
      mVerbTypeREmphatic = complexWordToCopy.mVerbTypeREmphatic;
      roverOrderNegationBeforeEmphatic = complexWordToCopy.roverOrderNegationBeforeEmphatic;
      mNumberDigit = complexWordToCopy.mNumberDigit;
      mNumberModifier = complexWordToCopy.mNumberModifier;
      mNumberSuffix = complexWordToCopy.mNumberSuffix;
      mIsNumberLike = complexWordToCopy.mIsNumberLike;
    }

    public void setHomophoneNumber(int number) {
      // Used for filtering entries. If two entries have homophones, they must each have a
      // unique number.
      mHomophoneNumber = number;
    }

    public ComplexWord stripPrefix() {
      if (mIsNounCandidate) {
        return null;
      }

      // Count from 1, since index 0 corresponds to no prefix.
      for (int i = 1; i < verbPrefixString.length; i++) {
        // Log.d(TAG, "checking prefix: " + verbPrefixString[i]);
        if (mUnparsedPart.startsWith(verbPrefixString[i])) {
          String partWithPrefixRemoved = mUnparsedPart.substring(verbPrefixString[i].length());
          // Log.d(TAG, "found prefix: " + verbPrefixString[i] + ", remainder: " +
          // partWithPrefixRemoved);
          if (!partWithPrefixRemoved.equals("")) {
            ComplexWord anotherComplexWord = new ComplexWord(partWithPrefixRemoved, this);
            anotherComplexWord.mVerbPrefix = i;
            return anotherComplexWord;
          }
        }
      }
      return null;
    }

    // Attempt to strip off the rovers.
    private ComplexWord stripRovers() {
      // There are a few entries in the database where the {-be'} and {-qu'} are included, e.g.,
      // {motlhbe'} and {Say'qu'}. The logic here allows, e.g., {bImotlhbe'be'}, but we don't care
      // since this is relatively rare. Note that {qu'be'} is itself a word.
      if (mVerbTypeRNegation == ROVER_NOT_YET_FOUND
          && mUnparsedPart.endsWith("be'")
          && !mUnparsedPart.equals("be'")) {
        String partWithRoversRemoved = mUnparsedPart.substring(0, mUnparsedPart.length() - 3);
        ComplexWord anotherComplexWord = new ComplexWord(partWithRoversRemoved, this);
        mVerbTypeRNegation = IGNORE_THIS_ROVER;
        anotherComplexWord.mVerbTypeRNegation = mSuffixLevel - 1;
        anotherComplexWord.mSuffixLevel = mSuffixLevel;
        if (anotherComplexWord.mVerbTypeREmphatic == mSuffixLevel - 1) {
          // {-be'qu'}
          anotherComplexWord.roverOrderNegationBeforeEmphatic = true;
        }
        if (BuildConfig.DEBUG) {
          Log.d(TAG, "found rover: -be'");
        }
        return anotherComplexWord;
      } else if (mVerbTypeREmphatic == ROVER_NOT_YET_FOUND
          && mUnparsedPart.endsWith("qu'")
          && !mUnparsedPart.equals("qu'")) {
        String partWithRoversRemoved = mUnparsedPart.substring(0, mUnparsedPart.length() - 3);
        ComplexWord anotherComplexWord = new ComplexWord(partWithRoversRemoved, this);
        mVerbTypeREmphatic = IGNORE_THIS_ROVER;
        anotherComplexWord.mVerbTypeREmphatic = mSuffixLevel - 1;
        anotherComplexWord.mSuffixLevel = mSuffixLevel;
        if (anotherComplexWord.mVerbTypeRNegation == mSuffixLevel - 1) {
          // {-qu'be'}
          anotherComplexWord.roverOrderNegationBeforeEmphatic = false;
        }
        if (BuildConfig.DEBUG) {
          Log.d(TAG, "found rover: -qu'");
        }
        return anotherComplexWord;
      }
      return null;
    }

    // Attempt to strip off one level of suffix from self, if this results in a branch return the
    // branch as a new complex word.
    // At the end of this call, this complex word will have have decreased one suffix level.
    public ComplexWord stripSuffixAndBranch() {
      if (mSuffixLevel == 0) {
        // This should never be reached.
        if (BuildConfig.DEBUG) {
          Log.e(TAG, "stripSuffixAndBranch: mSuffixLevel == 0");
        }
        return null;
      }

      // TODO: Refactor this to merge the two subtractions together.
      String[] suffixes;
      if (mIsNounCandidate) {
        // The types are 1-indexed, but the array is 0-index, so decrement it here.
        mSuffixLevel--;
        suffixes = nounSuffixesStrings[mSuffixLevel];
      } else {
        ComplexWord anotherComplexWord = stripRovers();
        String suffixType;
        if (anotherComplexWord != null) {
          if (BuildConfig.DEBUG) {
            // Verb suffix level doesn't correspond exactly: {-Ha'}, types 1 through 8, {-Qo'}, then
            // 9.
            if (mSuffixLevel == 1) {
              suffixType = "-Ha'";
            } else if (mSuffixLevel == 10) {
              suffixType = "-Qo'";
            } else if (mSuffixLevel == 11) {
              suffixType = "type 9";
            } else {
              suffixType = "type " + (mSuffixLevel - 1);
            }
            Log.d(TAG, "rover found while processing verb suffix: " + suffixType);
          }
          return anotherComplexWord;
        }
        // The types are 1-indexed, but the array is 0-index, so decrement it here.
        mSuffixLevel--;
        suffixes = verbSuffixesStrings[mSuffixLevel];
      }

      // Count from 1, since index 0 corresponds to no suffix of this type.
      for (int i = 1; i < suffixes.length; i++) {
        // Log.d(TAG, "checking suffix: " + suffixes[i]);
        if (mUnparsedPart.endsWith(suffixes[i])) {
          // Found a suffix of the current type, strip it.
          String partWithSuffixRemoved =
              mUnparsedPart.substring(0, mUnparsedPart.length() - suffixes[i].length());
          if (BuildConfig.DEBUG) {
            Log.d(TAG, "found suffix: " + suffixes[i] + ", remainder: " + partWithSuffixRemoved);
          }
          // A suffix was successfully stripped if there's something left. Also, if the suffix had
          // been {-oy}, check that the noun doesn't end in a vowel. The suffix {-oy} preceded by a
          // vowel is handled separately in maybeStripApostropheOy.
          if (!partWithSuffixRemoved.equals("") &&
              (suffixes[i] != "oy" || !partWithSuffixRemoved.matches(".*[aeIou]"))) {
            ComplexWord anotherComplexWord = new ComplexWord(partWithSuffixRemoved, this);
            // mSuffixLevel already decremented above.
            anotherComplexWord.mSuffixLevel = mSuffixLevel;
            if (mIsNounCandidate) {
              anotherComplexWord.mNounSuffixes[anotherComplexWord.mSuffixLevel] = i;
            } else {
              anotherComplexWord.mVerbSuffixes[anotherComplexWord.mSuffixLevel] = i;
            }
            return anotherComplexWord;
          }
        }
      }
      return null;
    }

    // Special-case processing for the suffix {-oy} when preceded by a vowel.
    public ComplexWord maybeStripApostropheOy() {
        if (mSuffixLevel == 1 && mIsNounCandidate && mUnparsedPart.endsWith("'oy")) {
            // Remove "'oy" from the end.
            String partWithSuffixRemoved = mUnparsedPart.substring(0, mUnparsedPart.length() - 3);
            if (partWithSuffixRemoved.matches(".*[aeIou]")) {
                ComplexWord anotherComplexWord = new ComplexWord(partWithSuffixRemoved, this);
                anotherComplexWord.mSuffixLevel = 0;  // No more suffixes.
                anotherComplexWord.mNounSuffixes[0] = 3;  // Index of "oy".
                return anotherComplexWord;
             }
        }
        return null;
    }

    private boolean hasNoMoreSuffixes() {
      return mSuffixLevel == 0;
    }

    // Returns true if this is not a complex word after all.
    public boolean isBareWord() {
      if (mVerbPrefix != 0) {
        // A verb prefix was found.
        return false;
      }
      if (mVerbTypeRNegation >= 0 || mVerbTypeREmphatic >= 0) {
        // Note that -1 indicates ROVER_NOT_YET_FOUND and -2 indicates IGNORE_THIS_ROVER, so a found
        // rover has position greater than or equal to 0.
        // A rover was found.
        return false;
      }
      for (int i = 0; i < mNounSuffixes.length; i++) {
        if (mNounSuffixes[i] != 0) {
          // A noun suffix was found.
          return false;
        }
      }
      for (int j = 0; j < mVerbSuffixes.length; j++) {
        if (mVerbSuffixes[j] != 0) {
          // A verb suffix was found.
          return false;
        }
      }
      // None found.
      return true;
    }

    public boolean isNumberLike() {
      // A complex word is number-like if it's a noun and it's marked as such.
      return mIsNounCandidate && mIsNumberLike;
    }

    private boolean noNounSuffixesFound() {
      for (int i = 0; i < mNounSuffixes.length; i++) {
        if (mNounSuffixes[i] != 0) {
          // A noun suffix was found.
          return false;
        }
      }
      // None found.
      return true;
    }

    @Override
    public String toString() {
      String s = mUnparsedPart;
      if (mIsNounCandidate) {
        s += " (n)";
        for (int i = 0; i < mNounSuffixes.length; i++) {
          s += " " + mNounSuffixes[i];
        }
      } else {
        // TODO: Handle negation and emphatic rovers.
        s += " (v) ";
        for (int i = 0; i < mVerbSuffixes.length; i++) {
          s += " " + mVerbSuffixes[i];
        }
      }
      return s;
    }

    // Used for telling stems of complex words apart.
    public String filter(boolean isLenient) {
      if (mIsVerbWithType5NounSuffix) {
        // If this is a candidate for a verb with a type 5 noun suffix attached, mark it so that
        // it's treated specially. In particular, it must be a verb which is not transitive, and it
        // cannot be a pronoun acting as a verb.
        return mUnparsedPart + ":v:n5";
      } else if (isLenient && isBareWord()) {
        // If isLenient is true, then also match non-nouns and non-verbs
        // if there are no prefixes or suffixes.
        return mUnparsedPart;
      }
      return mUnparsedPart
          + ":"
          + (mIsNounCandidate ? "n" : "v")
          + (mHomophoneNumber != -1 ? ":" + mHomophoneNumber : "");
    }

    public String stem() {
      return mUnparsedPart;
    }

    // Get the entry name for the verb prefix.
    public String getVerbPrefix() {
      return verbPrefixString[mVerbPrefix] + (mVerbPrefix == 0 ? "" : "-");
    }

    // Get the entry names for the verb suffixes.
    public String[] getVerbSuffixes() {
      String[] suffixes = new String[mVerbSuffixes.length];
      for (int i = 0; i < mVerbSuffixes.length; i++) {
        suffixes[i] = (mVerbSuffixes[i] == 0 ? "" : "-") + verbSuffixesStrings[i][mVerbSuffixes[i]];
      }
      return suffixes;
    }

    // Get the entry names for the noun suffixes.
    public String[] getNounSuffixes() {
      String[] suffixes = new String[mNounSuffixes.length];
      for (int i = 0; i < mNounSuffixes.length; i++) {
        suffixes[i] = (mNounSuffixes[i] == 0 ? "" : "-") + nounSuffixesStrings[i][mNounSuffixes[i]];
      }
      return suffixes;
    }

    // Get the root for a number.
    public String getNumberRoot() {
      if (mNumberDigit != 0) {
        // This is an actual digit from {wa'} to {Hut}.
        return numberDigitString[mNumberDigit];
      }

      String numberRoot = "";
      if (mUnparsedPart.startsWith("pagh")) {
        numberRoot = "pagh";
      } else if (mUnparsedPart.startsWith("Hoch")) {
        numberRoot = "Hoch";
      } else if (mUnparsedPart.startsWith("'ar")) {
        // Note that this will cause {'arDIch} to be accepted as a word.
        numberRoot = "'ar";
      }
      return numberRoot;
    }

    // Get the annotation for the root for a number.
    public String getNumberRootAnnotation() {
      if (mNumberDigit != 0) {
        return "n:num";
      }

      String numberRoot = "";
      if (mUnparsedPart.startsWith("pagh")) {
        numberRoot = "n:num";
      } else if (mUnparsedPart.startsWith("Hoch")) {
        // {Hoch} is a noun but not a number.
        numberRoot = "n";
      } else if (mUnparsedPart.startsWith("'ar")) {
        // {'ar} is a question word.
        numberRoot = "ques";
      } else {
        // This should never happen.
        if (BuildConfig.DEBUG) {
          Log.e(TAG, "getNumberRootAnnotation: else case reached");
        }
      }
      return numberRoot;
    }

    // Get the number modifier for a number.
    public String getNumberModifier() {
      return numberModifierString[mNumberModifier];
    }

    // Get the number suffix ("DIch" or "logh") for a number.
    public String getNumberSuffix() {
      return mNumberSuffix;
    }

    // Get the rovers at a given suffix level.
    public String[] getRovers(int suffixLevel) {
      final String[] negationThenEmphatic = {"-be'", "-qu'"};
      final String[] emphaticThenNegation = {"-qu'", "-be'"};
      final String[] negationOnly = {"-be'"};
      final String[] emphaticOnly = {"-qu'"};
      final String[] none = {};
      if (mVerbTypeRNegation == suffixLevel && mVerbTypeREmphatic == suffixLevel) {
        return (roverOrderNegationBeforeEmphatic ? negationThenEmphatic : emphaticThenNegation);
      } else if (mVerbTypeRNegation == suffixLevel) {
        return negationOnly;
      } else if (mVerbTypeREmphatic == suffixLevel) {
        return emphaticOnly;
      }
      return none;
    }

    // For display.
    public String getVerbPrefixString() {
      return verbPrefixString[mVerbPrefix] + (mVerbPrefix == 0 ? "" : "- + ");
    }

    // For display.
    public String getSuffixesString() {
      String suffixesString = "";
      // Verb suffixes have to go first, since some can convert a verb to a noun.
      for (int i = 0; i < mVerbSuffixes.length; i++) {
        String[] suffixes = verbSuffixesStrings[i];
        if (mVerbSuffixes[i] != 0) {
          suffixesString += " + -";
          suffixesString += suffixes[mVerbSuffixes[i]];
        }
        if (mVerbTypeRNegation == i && mVerbTypeREmphatic == i) {
          if (roverOrderNegationBeforeEmphatic) {
            suffixesString += " + -be' + qu'";
          } else {
            suffixesString += " + -qu' + be'";
          }
        } else if (mVerbTypeRNegation == i) {
          suffixesString += " + -be'";
        } else if (mVerbTypeREmphatic == i) {
          suffixesString += " + -qu'";
        }
      }
      // Noun suffixes.
      for (int j = 0; j < mNounSuffixes.length; j++) {
        String[] suffixes = nounSuffixesStrings[j];
        if (mNounSuffixes[j] != 0) {
          suffixesString += " + -";
          suffixesString += suffixes[mNounSuffixes[j]];
        }
      }
      return suffixesString;
    }

    public ComplexWord getAdjectivalVerbWithType5NounSuffix() {
      // Note that even if there is a rover, which is legal on a verb acting adjectivally,
      // it's hidden by the type 5 noun suffix and hence at this point we consider the
      // word a bare word.
      if (mIsNounCandidate || !isBareWord()) {
        // This should never be reached.
        if (BuildConfig.DEBUG) {
          Log.e(TAG, "getAdjectivalVerbWithType5NounSuffix: is noun candidate or is not bare word");
        }
        return null;
      }

      // Count from 1 since 0 corresponds to no such suffix.
      // Note that {-mo'} is both a type 5 noun suffix and a type 9 verb suffix.
      for (int i = 1; i < nounType5String.length; i++) {
        if (mUnparsedPart.endsWith(nounType5String[i])) {
          String adjectivalVerb =
              mUnparsedPart.substring(0, mUnparsedPart.length() - nounType5String[i].length());
          ComplexWord adjectivalVerbWithType5NounSuffix =
              new ComplexWord(adjectivalVerb, /* isNounCandidate */ false);

          // Note that type 5 corresponds to index 4 since the array is 0-indexed.
          adjectivalVerbWithType5NounSuffix.mNounSuffixes[4] = i;
          adjectivalVerbWithType5NounSuffix.mIsVerbWithType5NounSuffix = true;

          // Done processing.
          adjectivalVerbWithType5NounSuffix.mSuffixLevel = 0;

          // Since none of the type 5 noun suffixes are a prefix of another, it's okay to return
          // here.
          return adjectivalVerbWithType5NounSuffix;
        }
      }
      return null;
    }

    public ComplexWord getVerbRootIfNoun() {
      if (!mIsNounCandidate || !hasNoMoreSuffixes()) {
        // Should never be reached if there are still suffixes remaining.
        return null;
      }
      // Log.d(TAG, "getVerbRootIfNoun on: " + mUnparsedPart);

      // If the unparsed part ends in a suffix that nominalises a verb ({-wI'}, {-ghach}), analysize
      // it further.
      // Do this only if there were noun suffixes, since the bare noun will be analysed as a verb
      // anyway.
      if (!noNounSuffixesFound()
          && (mUnparsedPart.endsWith("ghach") || mUnparsedPart.endsWith("wI'"))) {
        // Log.d(TAG, "Creating verb from: " + mUnparsedPart);
        ComplexWord complexVerb = new ComplexWord(mUnparsedPart, /* complexWordToCopy */ this);
        complexVerb.mIsNounCandidate = false;
        complexVerb.mSuffixLevel = complexVerb.mVerbSuffixes.length;
        return complexVerb;
      }
      return null;
    }

    public void attachPrefix(String prefix) {
      if (mIsNounCandidate) {
        return;
      }
      for (int i = 1; i < verbPrefixString.length; i++) {
        if (prefix.equals(verbPrefixString[i] + "-")) {
          mVerbPrefix = i;
          break;
        }
      }
    }

    // Attaches a suffix. Returns the level of the suffix attached.
    public int attachSuffix(String suffix, boolean isNounSuffix, int verbSuffixLevel) {
      // Note that when a complex word noun is formed from a verb with {-wI'} or {-ghach}, its
      // stem is considered to be a verb. Furthermore, an adjectival verb can take a type 5
      // noun suffix. Noun suffixes can thus be attached to verbs. The isNounSuffix variable
      // here indicates the type of the suffix, not the type of the stem.

      // Special handling of {-DIch} and {-logh}.
      if (suffix.equals("-DIch") || suffix.equals("-logh")) {
        mIsNumberLike = true;
        mNumberSuffix = suffix.substring(1); // strip initial "-"
        return verbSuffixLevel;
      }

      if (isNounSuffix) {
        // This is a noun suffix. Iterate over noun suffix types.
        for (int i = 0; i < nounSuffixesStrings.length; i++) {
          // Count from 1, since 0 corresponds to no suffix of that type.
          for (int j = 1; j < nounSuffixesStrings[i].length; j++) {
            if (suffix.equals("-" + nounSuffixesStrings[i][j])) {
              mNounSuffixes[i] = j;

              // The verb suffix level hasn't changed.
              return verbSuffixLevel;
            }
          }
        }
      } else {
        // This is a verb suffix. Check if this is a true rover.
        if (suffix.equals("-be'")) {
          mVerbTypeRNegation = verbSuffixLevel;
          if (mVerbTypeREmphatic == verbSuffixLevel) {
            // {-qu'be'}
            roverOrderNegationBeforeEmphatic = false;
          }
          return verbSuffixLevel;
        } else if (suffix.equals("-qu'")) {
          mVerbTypeREmphatic = verbSuffixLevel;
          if (mVerbTypeRNegation == verbSuffixLevel) {
            // {-be'qu'}
            roverOrderNegationBeforeEmphatic = true;
          }
          return verbSuffixLevel;
        }
        // Iterate over verb suffix types.
        for (int i = 0; i < verbSuffixesStrings.length; i++) {
          // Count from 1, since 0 corresponds to no suffix of that type.
          for (int j = 1; j < verbSuffixesStrings[i].length; j++) {
            if (suffix.equals("-" + verbSuffixesStrings[i][j])) {
              mVerbSuffixes[i] = j;

              // The verb suffix level has been changed.
              return i;
            }
          }
        }
      }
      // This should never be reached.
      Log.e(TAG, "Unrecognised suffix: " + suffix);
      return verbSuffixLevel;
    }

    // Add this complex word to the list.
    private void addSelf(ArrayList<ComplexWord> complexWordsList) {
      if (!hasNoMoreSuffixes()) {
        // This point should never be reached.
        Log.e(
            TAG, "addSelf called on " + mUnparsedPart + " with suffix level " + mSuffixLevel + ".");
        return;
      }
      Log.d(TAG, "Found: " + this.toString());

      // Determine if this is a number. Assume that a number is of the form
      // "digit[modifier][suffix]",
      // where digit is {wa'}, etc., modifier is a power of ten such as {maH}, and suffix is one of
      // {-DIch} or {-logh}.
      if (mIsNounCandidate) {

        // Check for {-DIch} or {-logh}.
        String numberRoot = mUnparsedPart;
        if (mUnparsedPart.endsWith("DIch") || (isBareWord() && mUnparsedPart.endsWith("logh"))) {
          int rootLength = mUnparsedPart.length() - 4;
          numberRoot = mUnparsedPart.substring(0, rootLength);
          mNumberSuffix = mUnparsedPart.substring(rootLength);
        }

        // Check for a "power of ten" modifier, such as {maH}.
        // Count from 1, since 0 corresponds to no modifier.
        for (int i = 1; i < numberModifierString.length; i++) {
          if (numberRoot.endsWith(numberModifierString[i])) {
            mNumberModifier = i;
            numberRoot =
                numberRoot.substring(0, numberRoot.length() - numberModifierString[i].length());
            break;
          }
        }

        // Look for a digit from {wa'} to {Hut}. {pagh} is excluded for now.
        // Count from 1, since 0 corresponds to no digit.
        for (int j = 1; j < numberDigitString.length; j++) {
          if (numberRoot.equals(numberDigitString[j])) {
            // Found a digit, so this is a number.
            // Note that we leave mUnparsedPart alone, since we still want to add, e.g., {wa'DIch}
            // as a result.
            mNumberDigit = j;
            mIsNumberLike = true;
            break;
          }
        }
        // If there is no modifier or suffix, then ignore this as the bare
        // digit word will already be added.
        if (mNumberModifier == 0 && mNumberSuffix.equals("")) {
          mNumberDigit = 0;
          mIsNumberLike = false;
        }

        // Finally, treat these words specially: {'arlogh}, {paghlogh}, {Hochlogh}, {paghDIch},
        // {HochDIch}.
        if (!mNumberSuffix.equals("")
            && (numberRoot.equals("pagh")
                || numberRoot.equals("Hoch")
                || numberRoot.equals("'ar"))) {
          // We don't set mUnparsedPart to the root, because we still want the entire
          // word (e.g., {paghlogh}) to be added to the results if it is in the database.
          mIsNumberLike = true;
        }
      }

      // Add this complex word.
      if (BuildConfig.DEBUG) {
        Log.d(TAG, "adding word to complex words list: " + mUnparsedPart);
      }
      complexWordsList.add(this);
    }
  }

  // Attempt to parse this complex word, and if successful, add it to the given set.
  public static void parseComplexWord(
      String candidate, boolean isNounCandidate, ArrayList<ComplexWord> complexWordsList) {
    ComplexWord complexWord = new ComplexWord(candidate, isNounCandidate);
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "\n\n* parsing = " + candidate + " (" + (isNounCandidate ? "n" : "v") + ") *");
    }
    if (!isNounCandidate) {
      // Check prefix.
      ComplexWord strippedPrefixComplexWord = complexWord.stripPrefix();
      if (strippedPrefixComplexWord != null) {
        // Branch off a word with the prefix stripped.
        stripSuffix(strippedPrefixComplexWord, complexWordsList);
      }
    }
    // Check suffixes.
    stripSuffix(complexWord, complexWordsList);
  }

  // Helper method to strip a level of suffix from a word.
  private static void stripSuffix(
      ComplexWord complexWord, ArrayList<ComplexWord> complexWordsList) {
    if (complexWord.hasNoMoreSuffixes()) {
      if (BuildConfig.DEBUG) {
        Log.d(TAG, "attempting to add to complex words list: " + complexWord.mUnparsedPart);
      }
      complexWord.addSelf(complexWordsList);

      if (complexWord.mIsNounCandidate) {
        // Attempt to get the verb root of this word if it's a noun.
        complexWord = complexWord.getVerbRootIfNoun();
      } else if (complexWord.isBareWord()) {
        // Check for type 5 noun suffix on a possibly adjectival verb.
        complexWord = complexWord.getAdjectivalVerbWithType5NounSuffix();
        if (complexWord != null) {
          String adjectivalVerb = complexWord.stem();
          if (adjectivalVerb.endsWith("be'")
              || adjectivalVerb.endsWith("qu'")
              || adjectivalVerb.endsWith("Ha'")) {
            String adjectivalVerbWithoutRover =
                adjectivalVerb.substring(0, adjectivalVerb.length() - 3);
            // Adjectival verbs may end with a rover (except for {-Qo'}), so check for that here.
            ComplexWord anotherComplexWord =
                new ComplexWord(adjectivalVerbWithoutRover, complexWord);
            if (adjectivalVerb.endsWith("be'")) {
              anotherComplexWord.mVerbTypeRNegation = 0;
            } else if (adjectivalVerb.endsWith("qu'")) {
              anotherComplexWord.mVerbTypeREmphatic = 0;
            } else if (adjectivalVerb.endsWith("Ha'")) {
              anotherComplexWord.mVerbSuffixes[0] = 1;
            }
            stripSuffix(anotherComplexWord, complexWordsList);
          }
        }
      } else {
        // We're done.
        complexWord = null;
      }

      if (complexWord == null) {
        // Not a noun or the noun has no further verb root, so we're done with this complex word.
        return;
      }
      // Note that at this point we continue with a newly created complex word.
    }

    if (BuildConfig.DEBUG) {
      String suffixType;
      if (complexWord.mIsNounCandidate) {
        // Noun suffix level corresponds to the suffix type.
        suffixType = "type " + complexWord.mSuffixLevel;
      } else {
        // Verb suffix level doesn't correspond exactly: {-Ha'}, types 1 through 8, {-Qo'}, then 9.
        if (complexWord.mSuffixLevel == 1) {
          suffixType = "-Ha'";
        } else if (complexWord.mSuffixLevel == 10) {
          suffixType = "-Qo'";
        } else if (complexWord.mSuffixLevel == 11) {
          suffixType = "type 9";
        } else {
          suffixType = "type " + (complexWord.mSuffixLevel - 1);
        }
      }
      Log.d(
          TAG,
          "stripSuffix called on {"
              + complexWord.mUnparsedPart
              + "} for "
              + (complexWord.mIsNounCandidate ? "noun" : "verb")
              + " suffix: "
              + suffixType);
    }

    // Special check for the suffix {-oy} attached to a noun ending in a vowel. This needs to be
    // done additionally to the regular check, since it may be possible to parse a word either way,
    // e.g., {ghu'oy} could be {ghu} + {-'oy} or {ghu'} + {-oy}.
    ComplexWord apostropheOyComplexWord = complexWord.maybeStripApostropheOy();
    if (apostropheOyComplexWord != null) {
        // "'oy" was stripped, branch using it as a new candidate.
        stripSuffix(apostropheOyComplexWord, complexWordsList);
    }

    // Attempt to strip one level of suffix.
    ComplexWord strippedSuffixComplexWord = complexWord.stripSuffixAndBranch();
    if (strippedSuffixComplexWord != null) {
      // A suffix of the current type was found, branch using it as a new candidate.
      stripSuffix(strippedSuffixComplexWord, complexWordsList);
    }
    // Tail recurse to the next level of suffix. Note that the suffix level is decremented in
    // complexWord.stripSuffixAndBranch() above.
    stripSuffix(complexWord, complexWordsList);
  }
}
