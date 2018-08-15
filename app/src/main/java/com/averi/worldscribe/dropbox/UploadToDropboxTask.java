package com.averi.worldscribe.dropbox;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.averi.worldscribe.R;
import com.averi.worldscribe.utilities.FileRetriever;
import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.CreateFolderErrorException;
import com.dropbox.core.v2.files.WriteMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Created by mark on 24/12/16.
 *
 * <p>
 *     Code copied and modified from SitePoint:<br>
 *     https://www.sitepoint.com/adding-the-dropbox-api-to-an-android-app/
 * </p>
 *
 * <p>
 *     This AsyncTask handles file uploading to a Dropbox account. A Toast with an appropriate
 *     message is displayed at the end of file operations, depending on whether the upload was
 *     successful or not.
 * </p>
 */

public class UploadToDropboxTask extends AsyncTask {
    private DbxClientV2 dbxClient;
    private File file;
    private Context context;
    private boolean uploadSuccessful = true;
    private ProgressDialog progressDialog;
    private File errorLogFile;

    public UploadToDropboxTask(DbxClientV2 dbxClient, File file, Context context) {
        this.dbxClient = dbxClient;
        this.file = file;
        this.context = context;
        this.errorLogFile = generateErrorLogFile();
    }

    @Override
    protected void onPreExecute() {
        showProgressDialog();
    }

    /**
     * Displays a loading dialog that will stay on-screen while uploading occurs.
     */
    private void showProgressDialog() {
        String title = context.getString(R.string.dropboxUploadProgressTitle);
        String message = context.getString(R.string.dropboxUploadProgressMessage);
        progressDialog = ProgressDialog.show(context, title, message);
    }

    @Override
    protected Object doInBackground(Object[] params) {
        try {
            uploadRecursive(file);
        } catch (DbxException | IOException e) {
            try {
                PrintWriter errorLogPrintStream = new PrintWriter(errorLogFile);
                e.printStackTrace(errorLogPrintStream);
                errorLogPrintStream.close();
            } catch (FileNotFoundException fileNotFoundException) {
                Log.e("WorldScribe", fileNotFoundException.getMessage());
            }
            uploadSuccessful = false;
        }
        return null;
    }

    /**
     * Generates an empty error log file for Dropbox error logging purposes.
     * <p>
     *     This new file will overwrite any existing error log files that were created on the
     *     same day.
     * </p>
     * @return An empty error log file whose file name is based on the current date
     */
    private File generateErrorLogFile() {
        Date datum = new Date();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        String fullName = df.format(datum) + "_appLog.txt";

        File errorLogFile = new File(FileRetriever.getAppDirectory(), fullName);
        if (file.exists()) {
            file.delete();
        }
        try {
            errorLogFile.getParentFile().mkdirs();
            errorLogFile.createNewFile();
        } catch (IOException e) {
            Log.e("WorldScribe", e.getMessage());
        }

        return errorLogFile;
    }

    /**
     * This function was written by user6038288 on
     * <a href="https://stackoverflow.com/a/48007001">StackOverflow</a>.
     * @param context The Context from which this function is being called
     * @param file The file that will be attached to the email
     */
    private static void sendEmail(Context context, File file) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"averistudios@gmail.com"});
        intent.putExtra(Intent.EXTRA_SUBJECT, "WorldScribe " + file.getName());
        intent.putExtra(Intent.EXTRA_TEXT, "");
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(context, "Attachment Error", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("file://" + file);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        context.startActivity(Intent.createChooser(intent, "Send email..."));
    }

    /**
     * Upload the given file to Dropbox; if it is a directory, its contents will be recursively
     * uploaded.
     * @param fileBeingUploaded The file to upload to the client's Dropbox account
     * @throws DbxException If an error occurs with accessing the client's Dropbox account
     * @throws IOException If an error occurs with file uploading
     */
    private void uploadRecursive(File fileBeingUploaded) throws DbxException, IOException  {
        if (fileBeingUploaded.exists()) {
            String dropboxPath = getDropboxPath(fileBeingUploaded);
            if (dropboxPath == null) {
                throw new IOException("The Dropbox path ended up being 'null' for the following " +
                        "file: '" + fileBeingUploaded.getAbsolutePath() + "'");
            }

            if (fileBeingUploaded.isDirectory()) {
                try {
                    dbxClient.files().createFolder(dropboxPath);
                } catch (CreateFolderErrorException ex) {
                    // Checks if the exception was thrown because the folder already exists.
                    // That case isn't an error (as it just means we can skip over creating
                    // that folder), so we only want to throw the exception for other cases.
                    if (!(ex.errorValue.isPath() && ex.errorValue.getPathValue().isConflict())) {
                        throw ex;
                    }
                }

                File[] files = fileBeingUploaded.listFiles();
                for (File childFile : files) {
                    uploadRecursive(childFile);
                }

            } else {
                InputStream inputStream = new FileInputStream(fileBeingUploaded);
                dbxClient.files().uploadBuilder(dropboxPath)
                    .withMode(WriteMode.OVERWRITE)
                    .uploadAndFinish(inputStream);
            }
        }
    }

    /**
     * Returns the path of the given Android file on the client's Dropbox account.
     * @param file The file whose Dropbox path will be retrieved
     * @return The Dropbox file path of file
     */
    private String getDropboxPath(File file) {
        String androidFilePath = file.getAbsolutePath();

        String appFilePath = FileRetriever.getAppDirectory().getAbsolutePath();
        String dropboxPath = androidFilePath.replace(appFilePath, "");

        // Dropbox will not upload files that have a "." prefix.
        // To get around this, we upload those files without the "." prefix.
        String fileName = file.getName();
        if (fileName.startsWith(".")) {
            String fileNameWithoutDotPrefix = fileName.substring(1);
            dropboxPath = dropboxPath.replace(fileName, fileNameWithoutDotPrefix);
        }

        return dropboxPath;
    }

    @Override
    protected void onPostExecute(Object o) {
        super.onPostExecute(o);

        progressDialog.dismiss();
        showOutcomeDialog();
    }

    /**
     * Displays an AlertDialog telling the user whether or not the upload was successful.
     *
     * <p>
     *     If the upload was unsuccessful, the dialog will contain a checkbox asking the user to
     *     send an error log to the email address for Averi Studios.
     * </p>
     */
    private void showOutcomeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        String message;

        if (uploadSuccessful) {
            message = context.getString(R.string.dropboxUploadSuccess);
            builder.setPositiveButton(context.getString(R.string.dismissDropboxUploadOutcome), null);
        } else {
            message = context.getString(R.string.dropboxUploadFailure);

            LayoutInflater inflater = LayoutInflater.from(context);
            LinearLayout alertLayout = (LinearLayout) inflater.inflate(R.layout.layout_dropbox_error,
                    null);
            final CheckBox chkSendLog = (CheckBox) alertLayout.findViewById(R.id.chkSendLog);
            builder.setView(alertLayout);

            builder.setPositiveButton(context.getString(R.string.dismissDropboxUploadOutcome),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (chkSendLog.isChecked()) {
                                sendEmail(context, errorLogFile);
                            }
                        }
                    });
        }

        builder.setMessage(message);
        builder.create().show();
    }
}
