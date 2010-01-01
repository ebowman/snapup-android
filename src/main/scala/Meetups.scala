package meetup.example
 
import android.app.{Activity, ListActivity, AlertDialog, ProgressDialog}
import android.os.{Bundle, Environment, Looper, Handler, Message}
import android.widget.{ArrayAdapter, ListView, Toast, EditText, TextView, ImageView}
import android.view.{View, ViewGroup, Menu, MenuItem}
import android.net.Uri
import android.provider.MediaStore
import android.content.{Intent, DialogInterface}
import android.graphics.{BitmapFactory,Bitmap}
import android.util.Log

import dispatch.meetup._
import dispatch.Http
import dispatch.Http._
import dispatch.mime.Mime._
import java.io.{File,FileOutputStream}
import scala.actors.Actor._
import scala.actors.Futures._

import net.liftweb.json._
import net.liftweb.json.JsonAST._
 
class Meetups extends ListActivity with ScalaActivity {
  lazy val prefs = new Prefs(this)
  implicit def http = new Http

  lazy val meetups = Response.results(
    JsonParser.parse(getIntent.getExtras.getString("meetups"))
  ).toArray
  
  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setListAdapter(new ArrayAdapter(this, R.layout.row, meetups) {
      override def getView(position: Int, convertView: View, parent: ViewGroup) = {
        val row = View.inflate(Meetups.this, R.layout.row, null)
        val set: View => String => Unit = { case tv: TextView => tv.setText }
        val meetup = meetups(position)
        Event.name(meetup).foreach(set(row.findViewById(R.id.event_name)))
        Event.group_name(meetup).foreach(set(row.findViewById(R.id.group_name)))
        Event.photo_url(meetup).foreach { url =>
          row.findViewById(R.id.icon) match {
            case view: ImageView => actor {
              val bitmap = http(url >> { is =>
                BitmapFactory.decodeStream(is)
              })
              post { view.setImageBitmap(bitmap) }
            }
          }
        }
        row
      }
    })
  }
  val image_f = new File(Environment.getExternalStorageDirectory, "snapup-temp.jpg")
  def clean_image() { image_f.delete() }
  
  override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
    startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(image_f)), position)
  }
  override def onActivityResult(index: Int, result: Int, intent: Intent) {
    result match {
      case Activity.RESULT_OK =>
        val m = meetups(index)
        val event_name = Event.name(m).head
        val event_id = Event.id(m).head
        // insert as bitmap so gallery will own the file
        val bitmap = BitmapFactory.decodeFile(image_f.getPath)
        MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, event_name, Event.group_name(m).head)
        // save with max width for upload
        val max = 800
        if (bitmap.getWidth > max) {
          val smaller = Bitmap.createScaledBitmap(bitmap, max,
            (max.toFloat / bitmap.getWidth * bitmap.getHeight).toInt, true)
          smaller.compress(Bitmap.CompressFormat.JPEG, 85, new FileOutputStream(image_f))
        }
        get_caption(event_name) { caption =>
          try_upload(event_id, caption)
        }
      case _ => Toast.makeText(this, R.string.photo_cancelled, Toast.LENGTH_SHORT).show()
    }
  }
  
  def try_upload(event_id: String, caption: String) {
    val loading = new ProgressDialog(Meetups.this)
    loading.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
    loading.setTitle("Posting Photo to Meetup")
    loading.show()
    actor {
      try {
        Account.client(prefs) orElse { 
          error("somehow in Meetups#try_upload() without valid client")
        } foreach { cli =>
          http(cli(PhotoUpload.event_id(event_id).caption(caption).photo(image_f)) >?> { total => 
            post { loading.setMax(total.toInt) }
            val inc = total / 1000
            (bytes) => {
              if ((total - bytes) % inc == 0) post { loading.setProgress(bytes.toInt) } 
            }
          } >| )
        }
        image_f.delete()
        post { loading.dismiss() }
      } catch {
        case e => 
          Log.e("Meetups", "Error uploading photo", e)
          post {
            loading.dismiss()
            failed_dialog(event_id, caption)
          }
      }
    }
  }
  
  def get_caption[T](event_name: String)(block: String => Unit) {
    val input = new EditText(this)
    val dialog = new AlertDialog.Builder(this)
      .setTitle(event_name)
      .setMessage("Photo Caption: (optional)")
      .setPositiveButton("Post", new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, button: Int) {
          block(input.getText.toString)
        }
      }).setNegativeButton("Cancel", clean_image())
      .setOnCancelListener(clean_image())
      .create()
    dialog.setView(input, 15, 15, 15, 15)
    dialog.show()
  }
  
  def failed_dialog(event_id: String, caption: String) {
    new AlertDialog.Builder(this)
      .setTitle(R.string.upload_failed_title)
      .setMessage(R.string.upload_failed)
      .setPositiveButton("Retry", new DialogInterface.OnClickListener {
        def onClick(dialog: DialogInterface, which: Int) { try_upload(event_id, caption) }
      })
      .setNeutralButton("Okay", clean_image())
      .setOnCancelListener(clean_image())
      .create()
      .show()
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.main_menu, menu)
    true
  }

  override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
    case R.id.menu_item_reset => 
      (prefs.access.edit :: prefs.request.edit :: Nil) foreach { p =>
        p.clear()
        p.commit()
      }
      finish()
      true
    case _  => false
  }
}