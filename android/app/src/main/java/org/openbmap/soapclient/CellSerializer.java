/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.soapclient;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import org.openbmap.db.DataHelper;
import org.openbmap.db.DatabaseHelper;
import org.openbmap.db.Schema;
import org.openbmap.db.models.CellRecord;
import org.openbmap.db.models.LogFile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

/**
 * Exports cells to xml format for later upload.
 */
public class CellSerializer {
	private static final String TAG = CellSerializer.class.getSimpleName();

	/**
	 * Initial size wifi StringBuffer
	 */
	private static final int CELL_XML_DEFAULT_LENGTH = 220;

	/**
	 * Cursor windows size, to prevent running out of mem on to large cursor
	 */
	private static final int CURSOR_SIZE = 3000;

	/**
	 * XML templates
	 */
	private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>";

	private static final String LOG_XML = "\n<logfile manufacturer=\"%s\" model=\"%s\" revision=\"%s\" swid=\"%s\" swver=\"%s\" exportver=\"%s\">";

	private static final String SCAN_XML = "\n<scan time=\"%s\">";

	private static final String POSITION_XML = "\n\t<gps time=\"%s\" lng=\"%s\" lat=\"%s\" alt=\"%s\" hdg=\"%s\" spe=\"%s\" accuracy=\"%s\" type=\"%s\" />";


	/**
	 * XML template closing logfile
	 */
	private static final String	CLOSE_LOGFILE	= "\n</logfile>";

	/**
	 * XML template closing scan tag
	 */
	private static final String	CLOSE_SCAN_TAG	= "\n</scan>";

	/**
	 * Entries per log file
	 */
	private static final int CELLS_PER_FILE	= 1000;


	private final Context mContext;

	/**
	 * Session Id to export
	 */
	private final int mSession;

	/**
	 * Message in case of an error
	 */
	private final String errorMsg = null;

	/**
	 * Datahelper
	 */
	private final DataHelper mDataHelper;

	/**
	 * Directory where xmls files are stored
	 */
	private final String mTempPath;

	private int mColNetworkType;
	private int mColIsCdma;
	private int mColIsServing;
	private int mColIsNeigbor;
	private int mColLogicalCellId;
	private int mColActualCellId;
	private int mColUtranRnc;
	private int mColPsc;
	private int mColOperatorName;
	private int mColOperator;
	private int mColMcc;
	private int mColMnc;
	private int mColLac;
	private int mColStrengthDbm;
	private int mColTimestamp;
	private int mColPositionId;
	private int mColSessionId;

	private int	mColReqLat;

	private int	mColReqTimestamp;

	private int	mColReqLon;

	private int	mColReqAlt;

	private int	mColReqHead;

	private int	mColReqSpeed;

	private int	mColReqAcc;

	private int	mColBeginPosId;

	private int	mColEndPosId;

	private int	mColLastLat;

	private int	mColLastTimestamp;

	private int	mColLastLon;

	private int	mColLastAlt;

	private int	mColLastHead;

	private int	mColLastSpeed;

	private int	mColLastAcc;

	private int	mColStrengthAsu;


	/**
	 * Timestamp, required for file name generation
	 */
	//private Calendar mTimestamp;

	/**
	 * MCC code, generated by looking at the first cell, used for file names.
	 * This is also used for UMTS cells, which sometimes don't have a proper MCC reading (see Android documentation)
	 */
	private String	mActiveMcc;

	/**
	 * Two version numbers are tracked:
	 * 	- Radiobeacon version used for tracking (available from session record)
	 *  - Radiobeacon version used for exporting (available at runtime)
	 * mExportVersion describes the later
	 */
	private final String	mExportVersion;

	private static final String CELL_SQL_QUERY = " SELECT " + Schema.TBL_CELLS + "." + Schema.COL_ID + ", "
			+ Schema.COL_NETWORKTYPE + ", "
			+ Schema.COL_IS_CDMA + ", "
			+ Schema.COL_IS_SERVING + ", "
			+ Schema.COL_IS_NEIGHBOR + ", "
			+ Schema.COL_LOGICAL_CELLID + ", "
			+ Schema.COL_ACTUAL_CELLID + ", "
			+ Schema.COL_UTRAN_RNC + ", "
			+ Schema.COL_AREA + ", "
			+ Schema.COL_MCC + ", "
			+ Schema.COL_MNC + ", "
			+ Schema.COL_PSC + ", "
			+ Schema.COL_CDMA_BASEID + ", "
			+ Schema.COL_CDMA_NETWORKID + ", "
			+ Schema.COL_CDMA_SYSTEMID + ", "
			+ Schema.COL_OPERATORNAME + ", "
			+ Schema.COL_OPERATOR + ", "
			+ Schema.COL_STRENGTHDBM + ", "
			+ Schema.COL_STRENGTHASU + ", "
			+ Schema.TBL_CELLS + "." + Schema.COL_TIMESTAMP + ", "
			+ Schema.COL_BEGIN_POSITION_ID + ", "
			+ " \"req\".\"latitude\" AS \"req_latitude\","
			+ " \"req\".\"longitude\" AS \"req_longitude\","
			+ " \"req\".\"altitude\" AS \"req_altitude\","
			+ " \"req\".\"accuracy\" AS \"req_accuracy\","
			+ " \"req\".\"timestamp\" AS \"req_timestamp\","
			+ " \"req\".\"bearing\" AS \"req_bearing\","
			+ " \"req\".\"speed\" AS \"req_speed\", "
			+ " \"last\".\"latitude\" AS \"last_latitude\","
			+ " \"last\".\"longitude\" AS \"last_longitude\","
			+ " \"last\".\"altitude\" AS \"last_altitude\","
			+ " \"last\".\"accuracy\" AS \"last_accuracy\","
			+ " \"last\".\"timestamp\" AS \"last_timestamp\","
			+ " \"last\".\"bearing\" AS \"last_bearing\","
			+ " \"last\".\"speed\" AS \"last_speed\""
			+ " FROM " + Schema.TBL_CELLS
			+ " JOIN \"" + Schema.TBL_POSITIONS + "\" AS \"req\" ON (" + Schema.COL_BEGIN_POSITION_ID + " = \"req\".\"_id\")"
			+ " JOIN \"" + Schema.TBL_POSITIONS + "\" AS \"last\" ON (" + Schema.COL_END_POSITION_ID + " = \"last\".\"_id\")"
			+ " WHERE " + Schema.TBL_CELLS + "." + Schema.COL_SESSION_ID + " = ?"
			+ " ORDER BY " + Schema.COL_BEGIN_POSITION_ID
			+ " LIMIT " + CURSOR_SIZE
			+ " OFFSET ?";

	/**
	 * Default constructor
	 * @param context	Activities' context
	 * @param session Session id to export
	 * @param tempPath (full) path where temp files are saved. Will be created, if not existing.
	 * @param exportVersion current Radiobeacon version (can differ from Radiobeacon version used for tracking)

	 */
	public CellSerializer(final Context context, final int session, String tempPath, final String exportVersion) {
		this.mContext = context;
		this.mSession = session;
		if (tempPath != null && !tempPath.endsWith(File.separator)) {
			tempPath = tempPath + File.separator;
		}
		this.mTempPath = tempPath;
		this.mExportVersion = exportVersion;
		//this.mTimestamp = Calendar.getInstance();

		ensureTempPath(mTempPath);

		mDataHelper = new DataHelper(context);
	}

	/**
	 * Ensures temp file folder is existing and writeable.
	 * If folder not yet exists, it is created
	 */
	private boolean ensureTempPath(final String path) {
		final File folder = new File(path);

		boolean folderAccessible = false;
		if (folder.exists() && folder.canWrite()) {
			folderAccessible = true;
		}

		if (!folder.exists()) {
			folderAccessible = folder.mkdirs();
		}
		return folderAccessible;
	}

	/**
	 * Builds cell xml files and saves/uploads them
	 * @return file names of generated files
	 */
	protected final ArrayList<String> export() {
		Log.d(TAG, "Start cell export. Data source: " + CELL_SQL_QUERY);

		final LogFile headerRecord = mDataHelper.loadLogFileBySession(mSession);

		final DatabaseHelper mDbHelper = new DatabaseHelper(mContext.getApplicationContext());

		final ArrayList<String> generatedFiles = new ArrayList<>();

		// get first CHUNK_SIZE records
		Cursor cursorCells = mDbHelper.getReadableDatabase().rawQuery(CELL_SQL_QUERY,
				new String[]{String.valueOf(mSession), String.valueOf(0)});

		// [start] init columns
		mColNetworkType = cursorCells.getColumnIndex(Schema.COL_NETWORKTYPE);
		mColIsCdma = cursorCells.getColumnIndex(Schema.COL_IS_CDMA);
		mColIsServing = cursorCells.getColumnIndex(Schema.COL_IS_SERVING);
		mColIsNeigbor = cursorCells.getColumnIndex(Schema.COL_IS_NEIGHBOR);
		mColLogicalCellId = cursorCells.getColumnIndex(Schema.COL_LOGICAL_CELLID);
		mColActualCellId = cursorCells.getColumnIndex(Schema.COL_ACTUAL_CELLID);
		mColUtranRnc = cursorCells.getColumnIndex(Schema.COL_UTRAN_RNC);
		mColPsc = cursorCells.getColumnIndex(Schema.COL_PSC);
		mColOperatorName = cursorCells.getColumnIndex(Schema.COL_OPERATORNAME);
		mColOperator = cursorCells.getColumnIndex(Schema.COL_OPERATOR);
		mColMcc = cursorCells.getColumnIndex(Schema.COL_MCC);
		mColMnc = cursorCells.getColumnIndex(Schema.COL_MNC);
		mColLac = cursorCells.getColumnIndex(Schema.COL_AREA);
		mColStrengthDbm = cursorCells.getColumnIndex(Schema.COL_STRENGTHDBM);
		mColStrengthAsu = cursorCells.getColumnIndex(Schema.COL_STRENGTHASU);
		mColTimestamp = cursorCells.getColumnIndex(Schema.COL_TIMESTAMP);
		mColBeginPosId = cursorCells.getColumnIndex(Schema.COL_BEGIN_POSITION_ID);
		mColEndPosId = cursorCells.getColumnIndex(Schema.COL_END_POSITION_ID);
		mColSessionId = cursorCells.getColumnIndex(Schema.COL_SESSION_ID);
		mColReqLat = cursorCells.getColumnIndex("req_" + Schema.COL_LATITUDE);
		mColReqTimestamp = cursorCells.getColumnIndex("req_" + Schema.COL_TIMESTAMP);
		mColReqLon = cursorCells.getColumnIndex("req_" + Schema.COL_LONGITUDE);
		mColReqAlt = cursorCells.getColumnIndex("req_" + Schema.COL_ALTITUDE);
		mColReqHead = cursorCells.getColumnIndex("req_" + Schema.COL_BEARING);
		mColReqSpeed = cursorCells.getColumnIndex("req_" + Schema.COL_SPEED);
		mColReqAcc = cursorCells.getColumnIndex("req_" + Schema.COL_ACCURACY);
		mColLastLat = cursorCells.getColumnIndex("last_" + Schema.COL_LATITUDE);
		mColLastTimestamp = cursorCells.getColumnIndex("last_" + Schema.COL_TIMESTAMP);
		mColLastLon = cursorCells.getColumnIndex("last_" + Schema.COL_LONGITUDE);
		mColLastAlt = cursorCells.getColumnIndex("last_" + Schema.COL_ALTITUDE);
		mColLastHead = cursorCells.getColumnIndex("last_" + Schema.COL_BEARING);
		mColLastSpeed = cursorCells.getColumnIndex("last_" + Schema.COL_SPEED);
		mColLastAcc = cursorCells.getColumnIndex("last_" + Schema.COL_ACCURACY);
		// [end]

		final long startTime = System.currentTimeMillis();

		mActiveMcc = determineActiveMcc(cursorCells);

		long outer = 0;
		// serialize
		while (!cursorCells.isAfterLast()) {
			long i = 0;
			while (!cursorCells.isAfterLast()) {
				// creates files of 100 wifis each
				Log.i(TAG, "Cycle " + i);

				final long fileTimeStamp = determineFileTimestamp(cursorCells);
				final String fileName  = mTempPath + generateFilename(mActiveMcc, fileTimeStamp);
				saveAndMoveCursor(fileName, headerRecord, cursorCells);

				i += CELLS_PER_FILE;
				generatedFiles.add(fileName);
			}

			// fetch next CURSOR_SIZE records
			outer += CURSOR_SIZE;
			cursorCells.close();
			cursorCells = mDbHelper.getReadableDatabase().rawQuery(CELL_SQL_QUERY,
					new String[]{String.valueOf(mSession),
					String.valueOf(outer)});
		}

		final long difference = System.currentTimeMillis() - startTime;
		Log.i(TAG, "Serialize cells took " + difference + " ms");

		cursorCells.close();
		cursorCells = null;
		mDbHelper.close();

		return generatedFiles;
	}


	/**
	 * Gets timestamp from current record
	 * @param cursor
	 * @return
	 */
	private long determineFileTimestamp(final Cursor cursor) {
		cursor.moveToPrevious();
		if (cursor.moveToNext()) {
			final long timestamp = cursor.getLong(mColReqTimestamp);
			cursor.moveToPrevious();
			return timestamp;
		}
		return 0;
	}

	/**
	 * Gets MCC and MNC information from first record
	 * @param cursor
	 * @return
	 */
	private String determineActiveMcc(final Cursor cursor) {
		if (cursor.moveToFirst()) {
			final String activeMcc = cursor.getString(mColMcc);
			// go back to initial position
			cursor.moveToPrevious();
			return activeMcc;
		}
		return null;
	}

	/**
	 * Builds a valid cell log file. The number of records per file is limited (CHUNK_SIZE). Once the limit is reached,
	 * a new file has to be created. The file is saved at the specific location.
	 * A log file file consists of an header with basic information on cell manufacturer and model, software id and version.
	 * Below the log file header, scans are inserted. Each scan can contain several wifis
	 * @see <a href="http://sourceforge.net/apps/mediawiki/myposition/index.php?title=Wifi_log_format">openBmap format specification</a>
	 * @param fileName Filename, including full path
	 * @param headerRecord Header information record
	 * @param cursor Cursor to read from
	 */
	private void saveAndMoveCursor(final String fileName, final LogFile headerRecord, final Cursor cursor) {

		// for performance reasons direct database access is used here (instead of content provider)
		try {
			cursor.moveToPrevious();

			File file = new File(fileName);

			Writer bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file.getAbsoluteFile()), "UTF-8"), 30 * 1024);

			// Write header
			bw.write(XML_HEADER);
			bw.write(logToXml(headerRecord.getManufacturer(), headerRecord.getModel(), headerRecord.getRevision(), headerRecord.getSwid(), headerRecord.getSwVersion(), mExportVersion));

			long previousBeginId = 0;
			String previousEnd = "";

			int i = 0;
			// Iterate cells cursor until last row reached or CELLS_PER_FILE is reached
			while (i < CELLS_PER_FILE && cursor.moveToNext()) {

				final long beginId = Long.valueOf(cursor.getString(mColBeginPosId));

				final String currentBegin = positionToXml(
						cursor.getLong(mColReqTimestamp),
						cursor.getDouble(mColReqLon),
						cursor.getDouble(mColReqLat),
						cursor.getDouble(mColReqAlt),
						cursor.getDouble(mColReqHead),
						cursor.getDouble(mColReqSpeed),
						cursor.getDouble(mColReqAcc),
						"begin");

				final String currentEnd = positionToXml(
						cursor.getLong(mColLastTimestamp),
						cursor.getDouble(mColLastLon),
						cursor.getDouble(mColLastLat),
						cursor.getDouble(mColLastAlt) ,
						cursor.getDouble(mColLastHead),
						cursor.getDouble(mColLastSpeed),
						cursor.getDouble(mColLastAcc),
						"end");

				if (i == 0) {
					// Write first scan and gps tag at the beginning
					bw.write(scanToXml(cursor.getLong(mColTimestamp)));
					bw.write(currentBegin);
				} else {
					// Later on, scan and gps tags are only needed, if we have a new scan
					if (beginId != previousBeginId) {

						// write end gps tag for previous scan
						bw.write(previousEnd);
						bw.write(CLOSE_SCAN_TAG);

						// Write new scan and gps tag
						// TODO distance calculation, seems optional
						bw.write(scanToXml(cursor.getLong(mColTimestamp)));
						bw.write(currentBegin);
					}
				}

				/*
				 *  At this point, we will always have an open scan and gps tag,
				 *  so write cell xml now
				 *	Note that for performance reasons all columns except colIsServing and colIsNeigbor
				 *	are casted to string for performance reasons
				 */
				// TODO UMTS/CDMA not properly serialized!!!! Basestation, System and Network Id missing!
				bw.write(cellToXML(
						cursor.getInt(mColIsServing),
						cursor.getInt(mColIsNeigbor),
						cursor.getString(mColMcc),
						cursor.getString(mColMnc),
						cursor.getString(mColLac),
						cursor.getString(mColLogicalCellId),
						cursor.getString(mColActualCellId),
						cursor.getString(mColUtranRnc),
						cursor.getString(mColStrengthDbm),
						cursor.getString(mColStrengthAsu),
						cursor.getInt(mColNetworkType),
						cursor.getString(mColPsc)));

				previousBeginId = beginId;
				previousEnd = currentEnd;

				i++;
			}

			// If we are at the last cell, close open scan and gps tag
			bw.write(previousEnd);
			bw.write(CLOSE_SCAN_TAG);

			bw.write(CLOSE_LOGFILE);
			// ensure that everything is really written out and close
			bw.close();
			file = null;
			bw = null;

		} catch (final IOException ioe) {
			cursor.close();
			Log.e(TAG, ioe.toString(), ioe);
		}
	}

	/**
	 * Generates cell xml
	 * @param isServing
	 * @param isNeighbour
	 * @param mcc
	 * @param mnc
	 * @param lac
	 * @param logicalId	logical cell id (lcid)
	 * @param actualId	actual cell id (cid), may equal logicalId on GSM networks
	 * @param rnc		radio network controller id
	 * @param strengthDbm
	 * @param strengthAsu
	 * @param type
	 * @param psc
	 * @return
	 */
	private static String cellToXML(final int isServing, final int isNeighbour,
			final String mcc, final String mnc, final String lac, final String logicalId, final String actualId, final String rnc, final String strengthDbm, final String strengthAsu, final int type, final String psc) {
		final StringBuffer s = new StringBuffer(CELL_XML_DEFAULT_LENGTH);
		if (isServing != 0) {
			s.append("\n\t\t<gsmserving mcc=\"");
			s.append(mcc);
			s.append("\"");
			s.append(" mnc=\"");
			s.append(mnc);
			s.append("\"");
			s.append(" lac=\"");
			s.append(lac);
			s.append("\"");
			s.append(" id=\"");
			s.append(logicalId);
			s.append("\"");
			s.append(" act_id=\"");
			s.append(actualId);
			s.append("\"");
			s.append(" rnc=\"");
			s.append(rnc);
			s.append("\"");
			s.append(" psc=\"");
			s.append(psc);
			s.append("\"");
			s.append(" ss=\"");
			s.append(strengthDbm);
			s.append("\"");
			s.append(" act=\"");
			s.append(CellRecord.TECHNOLOGY_MAP().get(type));
			s.append("\"");
			s.append(" rxlev=\"");
			s.append(strengthAsu);
			s.append("\"");
			s.append("/>");
		}

		if (isNeighbour != 0) {
			s.append("\n\t\t<gsmneighbour mcc=\"");
			s.append(mcc);
			s.append("\"");
			s.append(" mnc=\"");
			s.append(mnc);
			s.append("\"");
			s.append(" lac=\"");
			s.append(lac);
			s.append("\"");
			s.append(" id=\"");
			s.append(logicalId);
			s.append("\"");
			s.append(" act_id=\"");
			s.append(actualId);
			s.append("\"");
			s.append(" rnc=\"");
			s.append(rnc);
			s.append("\"");
			s.append(" psc=\"");
			s.append(psc);
			s.append("\"");
			s.append(" rxlev=\"");
			s.append(strengthAsu);
			s.append("\"");
			s.append(" act=\"");
			s.append(CellRecord.TECHNOLOGY_MAP().get(type));
			s.append("\"");
			s.append("/>");
		}

		return s.toString();
	}

	/**
	 * Generates scan tag
	 * @param timestamp
	 * @return
	 */
	private static String scanToXml(final long timestamp) {
		return String.format(SCAN_XML, timestamp);
	}

	/**
	 * Generates log file header
	 * @param manufacturer
	 * @param model
	 * @param revision
	 * @param swid
	 * @param swVersion
	 * @return log tag
	 */
	private static String logToXml(final String manufacturer, final String model, final String revision, final String swid, final String swVersion, final String exportVersion) {
		return String.format(LOG_XML, manufacturer, model, revision, swid, swVersion, exportVersion);
	}

	/**
	 * Generates position tag
	 * @param reqTime
	 * @param lng
	 * @param lat
	 * @param alt
	 * @param head
	 * @param speed
	 * @param acc
	 * @param type
	 * @return position tag
	 */
	private static String positionToXml(final long reqTime, final double lng, final double lat,
			final double alt, final double head, final double speed, final double acc, final String type) {
		return String.format(POSITION_XML, reqTime, lng, lat, alt, head, speed, acc, type);
	}

	/**
	 * Generates filename
	 * Template for cell logs:
	 * username_V2_250_log20120110201943-cellular.xml
	 * i.e. [username]_V[format version]_[mcc]_log[date]-cellular.xml
	 * Keep in mind, that openbmap server currently only accepts filenames following the above mentioned
	 * naming pattern, otherwise files are ignored.
	 * @return filename
	 */
	private String generateFilename(final String mcc, final long timestamp) {
		/**
		 * Option 1: generate filename by export time
		 */
		/*
		// Caution filename collisions possible, if called in less than a second
		SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
		formatter.setCalendar(mTimestamp);
		mTimestamp.add(Calendar.SECOND, 1);
		return "V2_" + mcc + "_log" + formatter.format(mTimestamp.getTime()) + "-cellular.xml";
		 */

		/**
		 * Option 2: generate by first timestamp in file
		 */
		return "V2_" + mcc + "_log" + String.valueOf(timestamp) + "-cellular.xml";
	}



}
