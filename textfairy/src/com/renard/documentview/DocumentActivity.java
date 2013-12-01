/*
 * Copyright (C) 2012,2013 Renard Wellnitz.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.renard.documentview;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Toast;

import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.renard.model.Document;
import com.renard.ocr.BaseDocumentActivitiy;
import com.renard.ocr.DocumentContentProvider;
import com.renard.ocr.DocumentContentProvider.Columns;
import com.renard.ocr.R;
import com.renard.ocr.help.HintDialog;

public class DocumentActivity extends BaseDocumentActivitiy implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final String STATE_DIALOG_SHOWN = "state_dialog_shown";
    private final  static String LOG_TAG = DocumentActivity.class.getSimpleName();

    public interface DocumentContainerFragment {
		public void setCursor(final Cursor cursor);

		public String getTextofCurrentlyShownDocument();
	}
    private static final int REQUEST_CODE_TTS_CHECK = 6;
	private static final int REQUEST_CODE_OPTIONS = 4;
	private static final int REQUEST_CODE_TABLE_OF_CONTENTS = 5;
	public static final String EXTRA_ACCURACY = "ask_for_title";

	private int mParentId;
	private Cursor mCursor;
	View mFragmentFrame;
    private boolean mResultDialogShown = false;
    private TextToSpeech mTextToSpeech= null;
    private  boolean mTtsReady = false;
    private DocumentActionCallback mActionCallback = new DocumentActionCallback();
    private ActionMode mActionMode;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.document_activity);
		mFragmentFrame = findViewById(R.id.document_fragment_container);
		init();
		// Load partially transparent black background
		// getSupportActionBar().setBackgroundDrawable(getResources().getDrawable(R.drawable.ab_bg_black));
        int accuracy = getIntent().getIntExtra(EXTRA_ACCURACY,0);
        if(savedInstanceState!=null){
            mResultDialogShown = savedInstanceState.getBoolean(STATE_DIALOG_SHOWN);
        }
        if (accuracy>0 && !mResultDialogShown){
            mResultDialogShown = true;
            OCRResultDialog.newInstance(accuracy).show(getSupportFragmentManager(),OCRResultDialog.TAG);
        }
		setDocumentFragmentType(true);
		initAppIcon(this, HINT_DIALOG_ID);

	}

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int accuracy = getIntent().getIntExtra(EXTRA_ACCURACY,0);
        mResultDialogShown = true;
        OCRResultDialog.newInstance(accuracy).show(getSupportFragmentManager(),OCRResultDialog.TAG);
    }

    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(STATE_DIALOG_SHOWN,mResultDialogShown);
    }

    @Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getSupportMenuInflater().inflate(R.menu.document_activity_options, menu);
		return true;
	}

    void exportAsPdf(){
        Set<Integer> idForPdf = new HashSet<Integer>();
        idForPdf.add(getParentId());
        new CreatePDFTask(idForPdf).execute();
    }

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.item_view_mode) {
			Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.document_fragment_container);
			if (fragment instanceof DocumentCurlFragment) {
				setDocumentFragmentType(true);
			} else if (fragment instanceof DocumentPagerFragment) {
				setDocumentFragmentType(false);
			}
			return true;
		} else if (itemId == R.id.item_text_options) {
			Intent i = new Intent(this, TextOptionsActivity.class);
			startActivityForResult(i, REQUEST_CODE_OPTIONS);
			return true;
		} else if (itemId == R.id.item_content) {
			Intent tocIndent = new Intent(this, TableOfContentsActivity.class);
			Uri uri = Uri.parse(DocumentContentProvider.CONTENT_URI + "/" + getParentId());
			tocIndent.setData(uri);
			startActivityForResult(tocIndent, REQUEST_CODE_TABLE_OF_CONTENTS);
			return true;
		} else if (itemId == R.id.item_delete) {
			Set<Integer> idToDelete = new HashSet<Integer>();
			idToDelete.add(getParentId());
			new DeleteDocumentTask(idToDelete, true).execute();
			return true;
		} else if (itemId == R.id.item_export_as_pdf) {
            exportAsPdf();
			return true;
		} else if (itemId == R.id.item_copy_to_clipboard) {
			copyTextToClipboard();
			return true;
		} else if(itemId == R.id.item_text_to_speech){
            startTextToSpeech();
            return true;
        }
		return super.onOptionsItemSelected(item);
	}


    private class DocumentActionCallback implements ActionMode.Callback,TextToSpeech.OnInitListener {

        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            getSupportMenuInflater().inflate(R.menu.tts_action_mode, menu);
            if (mTextToSpeech==null){
                Intent checkIntent = new Intent();
                checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
                startActivityForResult(checkIntent, 0);
            }
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            if (mTtsReady){
                //show play and stop button
                menu.findItem(R.id.item_play).setVisible(true);
                menu.findItem(R.id.item_stop).setVisible(true);
            } else {
                //TODO show progress
                menu.findItem(R.id.item_play).setVisible(false);
                menu.findItem(R.id.item_stop).setVisible(false);
            }
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
            switch(menuItem.getItemId()){
                case R.id.item_play:
                    mTextToSpeech.speak(getPlainDocumentText(),TextToSpeech.QUEUE_FLUSH,null);
                    break;
                case R.id.item_stop:
                    mTextToSpeech.stop();
                    actionMode.finish();
                    break;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
            if (mTtsReady){
                mTextToSpeech.stop();
            }

        }

        @Override
        public void onInit(int status) {
            // status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
            if (status == TextToSpeech.ERROR){
                Log.e(LOG_TAG, "Could not initialize TextToSpeech.");
                //TODO show error dialog or toast
            } else {
                //TODO save document language and use it
                //TODO find out what languages are supported
                int result = mTextToSpeech.setLanguage(Locale.GERMAN);

                switch(result){
                    case TextToSpeech.LANG_MISSING_DATA:
                        Log.e(LOG_TAG, "LANG_MISSING_DATA");
                        //TODO show error dialog, allow user to open settings for loading the language
                        break;
                    case TextToSpeech.LANG_NOT_SUPPORTED:
                        Log.e(LOG_TAG, "LANG_NOT_SUPPORTED");
                        //TODO show error dialog, allow user to select different language
                        break;
                    default:
                        mActionMode.getMenu().findItem(R.id.item_play).setVisible(true);
                        mActionMode.getMenu().findItem(R.id.item_stop).setVisible(true);
                        mTtsReady = true;
                        return;
                }
            }
            if (mActionMode!=null){
                mActionMode.finish();
            }

        }
    }


    @Override
    protected synchronized void onDestroy() {
        super.onDestroy();
        if (mTextToSpeech!=null){
            mTextToSpeech.shutdown();
            mTextToSpeech = null;
        }
    }

    void startTextToSpeech() {
        mActionMode = startActionMode(mActionCallback);
    }


    void copyTextToClipboard() {
        final String text = getPlainDocumentText();
		//some apps don't like html text
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
//			copyHtmlTextToClipboard(htmlText, text);
//		} else 
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			copyTextToClipboardNewApi(text);
		} else {
			copyTextToClipboard(text);
		}

		Toast.makeText(this, getString(R.string.text_was_copied_to_clipboard), Toast.LENGTH_LONG).show();
	}

    private String getPlainDocumentText() {
        final String htmlText = getDocumentContainer().getTextofCurrentlyShownDocument();
        return Html.fromHtml(htmlText).toString();
    }

    @SuppressLint("NewApi")
	private void copyTextToClipboardNewApi(final String text) {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData clip = ClipData.newPlainText(getString(R.string.app_name), text);
		clipboard.setPrimaryClip(clip);
	}

	@SuppressWarnings("deprecation")
	private void copyTextToClipboard(String text) {
		android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		clipboard.setText("text");
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_TTS_CHECK){
                mTtsReady = false;
                mTextToSpeech = new TextToSpeech(this, mActionCallback);

                if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                    // success, create the TTS instance
                    mTextToSpeech = new TextToSpeech(this, mActionCallback);
                } else {
                    mActionMode.finish();
                    // missing data, install it
                    Intent installIntent = new Intent();
                    installIntent.setAction(
                            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    startActivity(installIntent);
                }
        } else if (requestCode == REQUEST_CODE_OPTIONS) {
			Fragment frag = getSupportFragmentManager().findFragmentById(R.id.document_fragment_container);
			if (frag instanceof DocumentPagerFragment) {
				DocumentPagerFragment pagerFragment = (DocumentPagerFragment) frag;
				pagerFragment.applyTextPreferences();
			}
		} else if (resultCode == RESULT_OK) {
			switch (requestCode) {
			case REQUEST_CODE_TABLE_OF_CONTENTS:
				int documentPos = data.getIntExtra(TableOfContentsActivity.EXTRA_DOCUMENT_POS, -1);
				DocumentContainerFragment fragment = (DocumentContainerFragment) getSupportFragmentManager().findFragmentById(R.id.document_fragment_container);
				if (fragment != null) {
					if (fragment instanceof DocumentCurlFragment) {
						((DocumentCurlFragment) fragment).setDisplayedPage(documentPos);
					} else {
						((DocumentPagerFragment) fragment).setDisplayedPage(documentPos);
					}
				}
				break;
			}
		}
	}

	@Override
	protected Dialog onCreateDialog(int id, Bundle args) {
		switch (id) {
		case HINT_DIALOG_ID:
			return HintDialog.createDialog(this, R.string.document_help_title, "file:///android_res/raw/document_help.html");
		}
		return super.onCreateDialog(id, args);
	}

	private void init() {
		String id = getIntent().getData().getLastPathSegment();
		int parentId = getParentId(getIntent().getData());
		// Base class needs that value
		if (parentId == -1) {
			mParentId = Integer.parseInt(id);
		} else {
			mParentId = parentId;
		}
		getSupportLoaderManager().initLoader(0, null, this).forceLoad();

	}

	private int getParentId(Uri documentUri) {
		int parentId = -1;
		Cursor c = getContentResolver().query(documentUri, new String[] { Columns.PARENT_ID }, null, null, null);
		if (!c.moveToFirst()) {
			return parentId;
		}
		int index = c.getColumnIndex(Columns.PARENT_ID);
		if (index > -1) {
			parentId = c.getInt(index);
		}
		c.close();
		return parentId;
	}

	@Override
	protected int getParentId() {
		return mParentId;
	}

	public DocumentContainerFragment getDocumentContainer() {
		DocumentContainerFragment fragment = (DocumentContainerFragment) getSupportFragmentManager().findFragmentById(R.id.document_fragment_container);
		return fragment;
	}

	public void setDocumentFragmentType(final boolean text) {
		// Check what fragment is shown, replace if needed.
		DocumentContainerFragment fragment = (DocumentContainerFragment) getSupportFragmentManager().findFragmentById(R.id.document_fragment_container);
		DocumentContainerFragment newFragment = null;
		if (text) {
			if ((fragment != null && fragment instanceof DocumentCurlFragment) || fragment == null) {
				newFragment = new DocumentPagerFragment();
			}
		} else if (!text) {
			if ((fragment != null && fragment instanceof DocumentPagerFragment) || fragment == null) {
				newFragment = new DocumentCurlFragment();
			}
		}
		if (newFragment != null) {
			if (mCursor != null) {
				newFragment.setCursor(mCursor);
			}
			FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
			if (fragment != null) {
				ft.remove((Fragment) fragment);
			}
			ft.add(R.id.document_fragment_container, (Fragment) newFragment);
			// ft.replace(R.id.document_fragment_container, (Fragment)
			// newFragment);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		mCursor = cursor;
		DocumentContainerFragment frag = getDocumentContainer();
		frag.setCursor(cursor);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mCursor = null;
	}

	@Override
	public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
		return new CursorLoader(this, DocumentContentProvider.CONTENT_URI, null, Columns.PARENT_ID + "=? OR " + Columns.ID + "=?", new String[] { String.valueOf(mParentId),
				String.valueOf(mParentId) }, "created ASC");
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();
		DocumentContainerFragment frag = getDocumentContainer();
		if (frag instanceof DocumentPagerFragment) {
			DocumentPagerFragment pagerFrag = (DocumentPagerFragment) frag;

			Pair<List<Uri>, List<Spanned>> documents = pagerFrag.getTextsToSave();

			if (documents != null && documents.first.size() > 0) {
				SaveDocumentTask saveTask = new SaveDocumentTask(documents.first, documents.second);
				saveTask.execute();
			}
		}

	}
}
