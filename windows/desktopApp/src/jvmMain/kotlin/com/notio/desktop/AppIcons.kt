package com.notio.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icons of the icon buttons (Android D278): the Material "outlined" icons we use, their path data copied from Material Icons 1.7.8
 * (Apache License 2.0, see NOTICE.md). Only these are in the app: the whole `material-icons-extended` library made the APK
 * 22 MB bigger, because the release build is not shrunk by R8.
 */
object AppIcons {
    val SelectAll: ImageVector by lazy { icon("SelectAll", "M3,5h2L5,3c-1.1,0,-2,0.9,-2,2ZM3,13h2v-2L3,11v2ZM7,21h2v-2L7,19v2ZM3,9h2L5,7L3,7v2ZM13,3h-2v2h2L13,3ZM19,3v2h2c0,-1.1,-0.9,-2,-2,-2ZM5,21v-2L3,19c0,1.1,0.9,2,2,2ZM3,17h2v-2L3,15v2ZM9,3L7,3v2h2L9,3ZM11,21h2v-2h-2v2ZM19,13h2v-2h-2v2ZM19,21c1.1,0,2,-0.9,2,-2h-2v2ZM19,9h2L21,7h-2v2ZM19,17h2v-2h-2v2ZM15,21h2v-2h-2v2ZM15,5h2L17,3h-2v2ZM7,17h10L17,7L7,7v10ZM9,9h6v6L9,15L9,9Z") }
    val Deselect: ImageVector by lazy { icon("Deselect", "M3,13h2v-2H3V13ZM7,21h2v-2H7V21ZM13,3h-2v2h2V3ZM19,3v2h2C21,3.9,20.1,3,19,3ZM5,21v-2H3C3,20.1,3.9,21,5,21ZM3,17h2v-2H3V17ZM11,21h2v-2h-2V21ZM19,13h2v-2h-2V13ZM19,9h2V7h-2V9ZM15,5h2V3h-2V5ZM7.83,5L7,4.17V3h2v2H7.83ZM19.83,17L19,16.17V15h2v2H19.83ZM21.19,21.19L2.81,2.81L1.39,4.22L4.17,7H3v2h2V7.83l2,2V17h7.17l2,2H15v2h2v-1.17l2.78,2.78L21.19,21.19ZM9,15v-3.17L12.17,15H9ZM15,12.17V9h-3.17l-2,-2H17v7.17L15,12.17Z") }
    val Delete: ImageVector by lazy { icon("Delete", "M16,9v10H8V9h8m-1.5,-6h-5l-1,1H5v2h14V4h-3.5l-1,-1ZM18,7H6v12c0,1.1,0.9,2,2,2h8c1.1,0,2,-0.9,2,-2V7Z") }
    val Checklist: ImageVector by lazy { icon("Checklist", "M22,7h-9v2h9V7ZM22,15h-9v2h9V15ZM5.54,11L2,7.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,11ZM5.54,19L2,15.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,19Z") }
    val PauseCircle: ImageVector by lazy { icon("PauseCircle", "M9,16h2V8H9V16ZM12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2ZM12,20c-4.41,0,-8,-3.59,-8,-8s3.59,-8,8,-8s8,3.59,8,8S16.41,20,12,20ZM13,16h2V8h-2V16Z") }
    val PlayCircle: ImageVector by lazy { icon("PlayCircle", "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2ZM12,20c-4.41,0,-8,-3.59,-8,-8s3.59,-8,8,-8s8,3.59,8,8S16.41,20,12,20ZM9.5,16.5l7,-4.5l-7,-4.5V16.5Z") }
    val History: ImageVector by lazy { icon("History", "M13,3c-4.97,0,-9,4.03,-9,9L1,12l3.89,3.89l0.07,0.14L9,12L6,12c0,-3.87,3.13,-7,7,-7s7,3.13,7,7s-3.13,7,-7,7c-1.93,0,-3.68,-0.79,-4.94,-2.06l-1.42,1.42C8.27,19.99,10.51,21,13,21c4.97,0,9,-4.03,9,-9s-4.03,-9,-9,-9ZM12,8v5l4.25,2.52l0.77,-1.28l-3.52,-2.09L13.5,8Z") }
    val Tune: ImageVector by lazy { icon("Tune", "M3,17v2h6v-2L3,17ZM3,5v2h10L13,5L3,5ZM13,21v-2h8v-2h-8v-2h-2v6h2ZM7,9v2L3,11v2h4v2h2L9,9L7,9ZM21,13v-2L11,11v2h10ZM15,9h2L17,7h4L21,5h-4L17,3h-2v6Z") }
    val UnfoldLess: ImageVector by lazy { icon("UnfoldLess", "M7.41,18.59L8.83,20L12,16.83L15.17,20l1.41,-1.41L12,14l-4.59,4.59ZM16.59,5.41L15.17,4L12,7.17L8.83,4L7.41,5.41L12,10l4.59,-4.59Z") }
    val UnfoldMore: ImageVector by lazy { icon("UnfoldMore", "M12,5.83L15.17,9l1.41,-1.41L12,3L7.41,7.59L8.83,9L12,5.83ZM12,18.17L8.83,15l-1.41,1.41L12,21l4.59,-4.59L15.17,15L12,18.17Z") }
    val Edit: ImageVector by lazy { icon("Edit", "M14.06,9.02l0.92,0.92L5.92,19L5,19v-0.92l9.06,-9.06M17.66,3c-0.25,0,-0.51,0.1,-0.7,0.29l-1.83,1.83l3.75,3.75l1.83,-1.83c0.39,-0.39,0.39,-1.02,0,-1.41l-2.34,-2.34c-0.2,-0.2,-0.45,-0.29,-0.71,-0.29ZM14.06,6.19L3,17.25L3,21h3.75L17.81,9.94l-3.75,-3.75Z") }
    val EditNote: ImageVector by lazy { icon("EditNote", "M3,10h11v2H3V10ZM3,8h11V6H3V8ZM3,16h7v-2H3V16ZM18.01,12.87l0.71,-0.71c0.39,-0.39,1.02,-0.39,1.41,0l0.71,0.71c0.39,0.39,0.39,1.02,0,1.41l-0.71,0.71L18.01,12.87ZM17.3,13.58l-5.3,5.3V21h2.12l5.3,-5.3L17.3,13.58Z") }
    /** The chat with the assistant (D145, the branch `assistant`). */
    val Chat: ImageVector by lazy { icon("Chat", "M20,2L4,2c-1.1,0,-1.99,0.9,-1.99,2L2,22l4,-4h14c1.1,0,2,-0.9,2,-2L22,4c0,-1.1,-0.9,-2,-2,-2ZM6,9h12v2L6,11L6,9ZM14,14L6,14v-2h8v2ZM18,8L6,8L6,6h12v2Z") }
    val ContentCopy: ImageVector by lazy { icon("ContentCopy", "M16,1L4,1c-1.1,0,-2,0.9,-2,2v14h2L4,3h12L16,1ZM19,5L8,5c-1.1,0,-2,0.9,-2,2v14c0,1.1,0.9,2,2,2h11c1.1,0,2,-0.9,2,-2L21,7c0,-1.1,-0.9,-2,-2,-2ZM19,21L8,21L8,7h11v14Z") }
    val FlipToFront: ImageVector by lazy { icon("FlipToFront", "M3,13h2v-2L3,11v2ZM3,17h2v-2L3,15v2ZM5,21v-2L3,19c0,1.1,0.89,2,2,2ZM3,9h2L5,7L3,7v2ZM15,21h2v-2h-2v2ZM19,3L9,3c-1.11,0,-2,0.9,-2,2v10c0,1.1,0.89,2,2,2h10c1.1,0,2,-0.9,2,-2L21,5c0,-1.1,-0.9,-2,-2,-2ZM19,15L9,15L9,5h10v10ZM11,21h2v-2h-2v2ZM7,21h2v-2L7,19v2Z") }
    val FlipToBack: ImageVector by lazy { icon("FlipToBack", "M9,7L7,7v2h2L9,7ZM9,11L7,11v2h2v-2ZM9,3c-1.11,0,-2,0.9,-2,2h2L9,3ZM13,15h-2v2h2v-2ZM19,3v2h2c0,-1.1,-0.9,-2,-2,-2ZM13,3h-2v2h2L13,3ZM9,17v-2L7,15c0,1.1,0.89,2,2,2ZM19,13h2v-2h-2v2ZM19,9h2L21,7h-2v2ZM19,17c1.1,0,2,-0.9,2,-2h-2v2ZM5,7L3,7v12c0,1.1,0.89,2,2,2h12v-2L5,19L5,7ZM15,5h2L17,3h-2v2ZM15,17h2v-2h-2v2Z") }
    val VerticalAlignTop: ImageVector by lazy { icon("VerticalAlignTop", "M8,11h3v10h2V11h3l-4,-4l-4,4ZM4,3v2h16V3H4Z") }
    val VerticalAlignBottom: ImageVector by lazy { icon("VerticalAlignBottom", "M16,13h-3V3h-2v10H8l4,4l4,-4ZM4,19v2h16v-2H4Z") }
    val Visibility: ImageVector by lazy { icon("Visibility", "M12,6c3.79,0,7.17,2.13,8.82,5.5C19.17,14.87,15.79,17,12,17s-7.17,-2.13,-8.82,-5.5C4.83,8.13,8.21,6,12,6m0,-2C7,4,2.73,7.11,1,11.5C2.73,15.89,7,19,12,19s9.27,-3.11,11,-7.5C21.27,7.11,17,4,12,4ZM12,9c1.38,0,2.5,1.12,2.5,2.5S13.38,14,12,14s-2.5,-1.12,-2.5,-2.5S10.62,9,12,9m0,-2c-2.48,0,-4.5,2.02,-4.5,4.5S9.52,16,12,16s4.5,-2.02,4.5,-4.5S14.48,7,12,7Z") }
    val VisibilityOff: ImageVector by lazy { icon("VisibilityOff", "M12,6c3.79,0,7.17,2.13,8.82,5.5c-0.59,1.22,-1.42,2.27,-2.41,3.12l1.41,1.41c1.39,-1.23,2.49,-2.77,3.18,-4.53C21.27,7.11,17,4,12,4c-1.27,0,-2.49,0.2,-3.64,0.57l1.65,1.65C10.66,6.09,11.32,6,12,6ZM10.93,7.14L13,9.21c0.57,0.25,1.03,0.71,1.28,1.28l2.07,2.07c0.08,-0.34,0.14,-0.7,0.14,-1.07C16.5,9.01,14.48,7,12,7c-0.37,0,-0.72,0.05,-1.07,0.14ZM2.01,3.87l2.68,2.68C3.06,7.83,1.77,9.53,1,11.5C2.73,15.89,7,19,12,19c1.52,0,2.98,-0.29,4.32,-0.82l3.42,3.42l1.41,-1.41L3.42,2.45L2.01,3.87ZM9.51,11.37l2.61,2.61c-0.04,0.01,-0.08,0.02,-0.12,0.02c-1.38,0,-2.5,-1.12,-2.5,-2.5c0,-0.05,0.01,-0.08,0.01,-0.13ZM6.11,7.97l1.75,1.75c-0.23,0.55,-0.36,1.15,-0.36,1.78c0,2.48,2.02,4.5,4.5,4.5c0.63,0,1.23,-0.13,1.77,-0.36l0.98,0.98c-0.88,0.24,-1.8,0.38,-2.75,0.38c-3.79,0,-7.17,-2.13,-8.82,-5.5c0.7,-1.43,1.72,-2.61,2.93,-3.53Z") }
    val Lock: ImageVector by lazy { icon("Lock", "M18,8h-1L17,6c0,-2.76,-2.24,-5,-5,-5S7,3.24,7,6v2L6,8c-1.1,0,-2,0.9,-2,2v10c0,1.1,0.9,2,2,2h12c1.1,0,2,-0.9,2,-2L20,10c0,-1.1,-0.9,-2,-2,-2ZM9,6c0,-1.66,1.34,-3,3,-3s3,1.34,3,3v2L9,8L9,6ZM18,20L6,20L6,10h12v10ZM12,17c1.1,0,2,-0.9,2,-2s-0.9,-2,-2,-2s-2,0.9,-2,2s0.9,2,2,2Z") }
    val LockOpen: ImageVector by lazy { icon("LockOpen", "M18,8h-1L17,6c0,-2.76,-2.24,-5,-5,-5S7,3.24,7,6h2c0,-1.66,1.34,-3,3,-3s3,1.34,3,3v2L6,8c-1.1,0,-2,0.9,-2,2v10c0,1.1,0.9,2,2,2h12c1.1,0,2,-0.9,2,-2L20,10c0,-1.1,-0.9,-2,-2,-2ZM18,20L6,20L6,10h12v10ZM12,17c1.1,0,2,-0.9,2,-2s-0.9,-2,-2,-2s-2,0.9,-2,2s0.9,2,2,2Z") }
    val AddToPhotos: ImageVector by lazy { icon("AddToPhotos", "M4,6L2,6v14c0,1.1,0.9,2,2,2h14v-2L4,20L4,6ZM20,2L8,2c-1.1,0,-2,0.9,-2,2v12c0,1.1,0.9,2,2,2h12c1.1,0,2,-0.9,2,-2L22,4c0,-1.1,-0.9,-2,-2,-2ZM20,16L8,16L8,4h12v12ZM13,15h2v-4h4L19,9h-4L15,5h-2v4L9,9v2h4Z") }
    val Fullscreen: ImageVector by lazy { icon("Fullscreen", "M7,14L5,14v5h5v-2L7,17v-3ZM5,10h2L7,7h3L10,5L5,5v5ZM17,17h-3v2h5v-5h-2v3ZM14,5v2h3v3h2L19,5h-5Z") }
    val Functions: ImageVector by lazy { icon("Functions", "M18,4H6v2l6.5,6L6,18v2h12v-3h-7l5,-5l-5,-5h7V4Z") }
    val PlaylistAdd: ImageVector by lazy { icon("PlaylistAdd", "M14,10H3v2h11V10ZM14,6H3v2h11V6ZM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2H18ZM3,16h7v-2H3V16Z") }
    val AddComment: ImageVector by lazy { icon("AddComment", "M22,4c0,-1.1,-0.9,-2,-2,-2L4,2c-1.1,0,-2,0.9,-2,2v12c0,1.1,0.9,2,2,2h14l4,4L22,4ZM20,17.17L18.83,16L4,16L4,4h16v13.17ZM13,5h-2v4L7,9v2h4v4h2v-4h4L17,9h-4Z") }
    val Calculate: ImageVector by lazy { icon("Calculate", "M19,3H5C3.9,3,3,3.9,3,5v14c0,1.1,0.9,2,2,2h14c1.1,0,2,-0.9,2,-2V5C21,3.9,20.1,3,19,3ZM19,19H5V5h14V19ZM6.25,7.72h5v1.5h-5ZM13,15.75h5v1.5h-5ZM13,13.25h5v1.5h-5ZM8,18l1.5,0l0,-2l2,0l0,-1.5l-2,0l0,-2l-1.5,0l0,2l-2,0l0,1.5l2,0ZM14.09,10.95l1.41,-1.41l1.41,1.41l1.06,-1.06l-1.41,-1.42l1.41,-1.41l-1.06,-1.06l-1.41,1.41l-1.41,-1.41l-1.06,1.06l1.41,1.41l-1.41,1.42Z") }
    val MoreTime: ImageVector by lazy { icon("MoreTime", "M10,8l0,6l4.7,2.9l0.8,-1.2l-4,-2.4l0,-5.3ZM17.92,12c0.05,0.33,0.08,0.66,0.08,1c0,3.9,-3.1,7,-7,7s-7,-3.1,-7,-7c0,-3.9,3.1,-7,7,-7c0.7,0,1.37,0.1,2,0.29V4.23C12.36,4.08,11.69,4,11,4c-5,0,-9,4,-9,9s4,9,9,9s9,-4,9,-9c0,-0.34,-0.02,-0.67,-0.06,-1H17.92ZM20,5l0,-3l-2,0l0,3l-3,0l0,2l3,0l0,3l2,0l0,-3l3,0l0,-2Z") }
    val VolumeOff: ImageVector by lazy { icon("VolumeOff", "M16.5,12c0,-1.77,-1.02,-3.29,-2.5,-4.03v2.21l2.45,2.45c0.03,-0.2,0.05,-0.41,0.05,-0.63ZM19,12c0,0.94,-0.2,1.82,-0.54,2.64l1.51,1.51C20.63,14.91,21,13.5,21,12c0,-4.28,-2.99,-7.86,-7,-8.77v2.06c2.89,0.86,5,3.54,5,6.71ZM4.27,3L3,4.27L7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52,-1.42,0.93,-2.25,1.18v2.06c1.38,-0.31,2.63,-0.95,3.69,-1.81L19.73,21L21,19.73l-9,-9L4.27,3ZM12,4L9.91,6.09L12,8.18V4Z") }
    val NotificationsOff: ImageVector by lazy { icon("NotificationsOff", "M12,22c1.1,0,2,-0.9,2,-2h-4c0,1.1,0.9,2,2,2ZM12,6.5c2.49,0,4,2.02,4,4.5v0.1l2,2L18,11c0,-3.07,-1.63,-5.64,-4.5,-6.32L13.5,4c0,-0.83,-0.67,-1.5,-1.5,-1.5s-1.5,0.67,-1.5,1.5v0.68c-0.24,0.06,-0.47,0.15,-0.69,0.23l1.64,1.64c0.18,-0.02,0.36,-0.05,0.55,-0.05ZM5.41,3.35L4,4.76l2.81,2.81C6.29,8.57,6,9.74,6,11v5l-2,2v1h14.24l1.74,1.74l1.41,-1.41L5.41,3.35ZM16,17L8,17v-6c0,-0.68,0.12,-1.32,0.34,-1.9L16,16.76L16,17Z") }
    val TaskAlt: ImageVector by lazy { icon("TaskAlt", "M22,5.18L10.59,16.6l-4.24,-4.24l1.41,-1.41l2.83,2.83l10,-10L22,5.18ZM19.79,10.22C19.92,10.79,20,11.39,20,12c0,4.42,-3.58,8,-8,8s-8,-3.58,-8,-8c0,-4.42,3.58,-8,8,-8c1.58,0,3.04,0.46,4.28,1.25l1.44,-1.44C16.1,2.67,14.13,2,12,2C6.48,2,2,6.48,2,12c0,5.52,4.48,10,10,10s10,-4.48,10,-10c0,-1.19,-0.22,-2.33,-0.6,-3.39L19.79,10.22Z") }
    val DeleteForever: ImageVector by lazy { icon("DeleteForever", "M14.12,10.47L12,12.59l-2.13,-2.12l-1.41,1.41L10.59,14l-2.12,2.12l1.41,1.41L12,15.41l2.12,2.12l1.41,-1.41L13.41,14l2.12,-2.12ZM15.5,4l-1,-1h-5l-1,1H5v2h14V4ZM6,19c0,1.1,0.9,2,2,2h8c1.1,0,2,-0.9,2,-2V7H6v12ZM8,9h8v10H8V9Z") }
    val FileDownload: ImageVector by lazy { icon("FileDownload", "M18,15v3H6v-3H4v3c0,1.1,0.9,2,2,2h12c1.1,0,2,-0.9,2,-2v-3H18ZM17,11l-1.41,-1.41L13,12.17V4h-2v8.17L8.41,9.59L7,11l5,5L17,11Z") }
    val CheckBox: ImageVector by lazy { icon("CheckBox", "M19,3H5c-1.1,0,-2,0.9,-2,2v14c0,1.1,0.9,2,2,2h14c1.1,0,2,-0.9,2,-2V5c0,-1.1,-0.9,-2,-2,-2ZM19,19H5V5h14v14ZM17.99,9l-1.41,-1.42l-6.59,6.59l-2.58,-2.57l-1.42,1.41l4,3.99Z") }
    val CheckBoxBlank: ImageVector by lazy { icon("CheckBoxBlank", "M19,5v14H5V5h14m0,-2H5c-1.1,0,-2,0.9,-2,2v14c0,1.1,0.9,2,2,2h14c1.1,0,2,-0.9,2,-2V5c0,-1.1,-0.9,-2,-2,-2Z") }
    val RestoreFromTrash: ImageVector by lazy { icon("RestoreFromTrash", "M15.5,4l-1,-1h-5l-1,1L5,4v2h14L19,4ZM6,19c0,1.1,0.9,2,2,2h8c1.1,0,2,-0.9,2,-2L18,7L6,7v12ZM8,14L8,9h8v10L8,19v-5ZM10,18h4v-4h2l-4,-4l-4,4h2Z") }

    val Description: ImageVector by lazy { icon("Description", "M14,2H6c-1.1,0,-1.99,0.9,-1.99,2L4,20c0,1.1,0.89,2,1.99,2H18c1.1,0,2,-0.9,2,-2V8l-6,-6ZM16,18H8v-2h8v2ZM16,14H8v-2h8v2ZM13,9V3.5L18.5,9H13Z") }
    val TextFormat: ImageVector by lazy { icon("TextFormat", "M5,17v2h14v-2H5ZM9.5,12.8h5l0.9,2.2h2.1L12.75,4h-1.5L6.5,15h2.1l0.9,-2.2ZM12,5.98L13.87,11h-3.74L12,5.98Z") }
    val Wallet: ImageVector by lazy { icon("Wallet", "M21,18v1c0,1.1,-0.9,2,-2,2H5c-1.11,0,-2,-0.9,-2,-2V5c0,-1.1,0.89,-2,2,-2h14c1.1,0,2,0.9,2,2v1h-9c-1.11,0,-2,0.9,-2,2v8c0,1.1,0.89,2,2,2h9ZM12,16h10V8H12v8ZM16,13.5c-0.83,0,-1.5,-0.67,-1.5,-1.5s0.67,-1.5,1.5,-1.5s1.5,0.67,1.5,1.5s-0.67,1.5,-1.5,1.5Z") }
    val Brush: ImageVector by lazy { icon("Brush", "M7,14c-1.66,0,-3,1.34,-3,3c0,1.31,-1.16,2,-2,2c0.92,1.22,2.49,2,4,2c2.21,0,4,-1.79,4,-4c0,-1.66,-1.34,-3,-3,-3ZM20.71,4.63l-1.34,-1.34c-0.39,-0.39,-1.02,-0.39,-1.41,0L9,12.25L11.75,15l8.96,-8.96c0.39,-0.39,0.39,-1.02,0,-1.41Z") }
    val Straighten: ImageVector by lazy { icon("Straighten", "M21,6H3c-1.1,0,-2,0.9,-2,2v8c0,1.1,0.9,2,2,2h18c1.1,0,2,-0.9,2,-2V8c0,-1.1,-0.9,-2,-2,-2ZM21,16H3V8h2v4h2V8h2v4h2V8h2v4h2V8h2v4h2V8h2v8Z") }
    val Mic: ImageVector by lazy { icon("Mic", "M12,14c1.66,0,2.99,-1.34,2.99,-3L15,5c0,-1.66,-1.34,-3,-3,-3S9,3.34,9,5v6c0,1.66,1.34,3,3,3ZM17.3,11c0,3,-2.54,5.1,-5.3,5.1S6.7,14,6.7,11H5c0,3.41,2.72,6.23,6,6.72V21h2v-3.28c3.28,-0.48,6,-3.3,6,-6.72h-1.7Z") }
    val Event: ImageVector by lazy { icon("Event", "M17,12h-5v5h5v-5ZM16,1v2H8V1H6v2H5c-1.11,0,-1.99,0.9,-1.99,2L3,19c0,1.1,0.89,2,2,2h14c1.1,0,2,-0.9,2,-2V5c0,-1.1,-0.9,-2,-2,-2h-1V1h-2ZM19,19H5V8h14v11Z") }
    val PhotoCamera: ImageVector by lazy { icon("PhotoCamera", "M9,2L7.17,4H4c-1.1,0,-2,0.9,-2,2v12c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V6c0,-1.1,-0.9,-2,-2,-2h-3.17L15,2H9ZM12,17c-2.76,0,-5,-2.24,-5,-5s2.24,-5,5,-5s5,2.24,5,5s-2.24,5,-5,5ZM12,8.8c-1.77,0,-3.2,1.43,-3.2,3.2s1.43,3.2,3.2,3.2s3.2,-1.43,3.2,-3.2s-1.43,-3.2,-3.2,-3.2Z") }
    val Videocam: ImageVector by lazy { icon("Videocam", "M17,10.5V7c0,-0.55,-0.45,-1,-1,-1H4c-0.55,0,-1,0.45,-1,1v10c0,0.55,0.45,1,1,1h12c0.55,0,1,-0.45,1,-1v-3.5l4,4v-11l-4,4Z") }
    val ScreenRecord: ImageVector by lazy { icon("ScreenRecord", "M21,2H3c-1.1,0,-2,0.9,-2,2v12c0,1.1,0.9,2,2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0,2,-0.9,2,-2V4c0,-1.1,-0.9,-2,-2,-2ZM21,16H3V4h18v12ZM12,7c-1.66,0,-3,1.34,-3,3s1.34,3,3,3s3,-1.34,3,-3s-1.34,-3,-3,-3Z") }
    val Folder: ImageVector by lazy { icon("Folder", "M10,4H4c-1.1,0,-1.99,0.9,-1.99,2L2,18c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V8c0,-1.1,-0.9,-2,-2,-2h-8l-2,-2Z") }
    val NewFolder: ImageVector by lazy { icon("NewFolder", "M20,6h-8l-2,-2H4c-1.11,0,-1.99,0.89,-1.99,2L2,18c0,1.11,0.89,2,2,2h16c1.11,0,2,-0.89,2,-2V8c0,-1.11,-0.89,-2,-2,-2ZM19,14h-3v3h-2v-3h-3v-2h3V9h2v3h3v2Z") }

    val ViewCards: ImageVector by lazy { icon("ViewCards", "M20,13H4c-0.55,0,-1,0.45,-1,1v6c0,0.55,0.45,1,1,1h16c0.55,0,1,-0.45,1,-1v-6c0,-0.55,-0.45,-1,-1,-1ZM20,3H4c-0.55,0,-1,0.45,-1,1v6c0,0.55,0.45,1,1,1h16c0.55,0,1,-0.45,1,-1V4c0,-0.55,-0.45,-1,-1,-1Z") }
    val ViewList: ImageVector by lazy { icon("ViewList", "M3,13h2v-2H3v2ZM3,17h2v-2H3v2ZM3,9h2V7H3v2ZM7,13h14v-2H7v2ZM7,17h14v-2H7v2ZM7,7v2h14V7H7Z") }
    val ViewGrid: ImageVector by lazy { icon("ViewGrid", "M3,3v8h8V3H3ZM3,13v8h8v-8H3ZM13,3v8h8V3h-8ZM13,13v8h8v-8h-8Z") }
    val Sort: ImageVector by lazy { icon("Sort", "M3,18h6v-2H3v2ZM3,6v2h18V6H3ZM3,13h12v-2H3v2Z") }
    val Schedule: ImageVector by lazy { icon("Schedule", "M11.99,2C6.47,2,2,6.48,2,12s4.47,10,9.99,10C17.52,22,22,17.52,22,12S17.52,2,11.99,2ZM12,20c-4.42,0,-8,-3.58,-8,-8s3.58,-8,8,-8s8,3.58,8,8s-3.58,8,-8,8ZM12.5,7H11v6l5.25,3.15l0.75,-1.23l-4.5,-2.67V7Z") }

    /** The icon of a kind of note (D298): the kinds are chosen by icon when a note is made. */
    fun forKind(typeId: String): ImageVector = when (typeId) {
        "WORD" -> TextFormat
        "TEXT" -> Description
        "TASK" -> TaskAlt
        "LIST" -> Checklist
        "BUDGET" -> Wallet
        "SCHEDULE" -> Event
        "DRAWING" -> Brush
        "BLUEPRINT" -> Straighten
        "AUDIO" -> Mic
        "PHOTO" -> PhotoCamera
        "VIDEO" -> Videocam
        "SCREEN" -> ScreenRecord
        else -> Description
    }

    val Close: ImageVector by lazy { icon("Close", "M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12Z") }
    val Check: ImageVector by lazy { icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19L21,7l-1.41,-1.41Z") }
    val Add: ImageVector by lazy { icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2Z") }
    val Save: ImageVector by lazy { icon("Save", "M17,3H5c-1.11,0,-2,0.9,-2,2v14c0,1.1,0.89,2,2,2h14c1.1,0,2,-0.9,2,-2V7l-4,-4ZM12,19c-1.66,0,-3,-1.34,-3,-3s1.34,-3,3,-3s3,1.34,3,3s-1.34,3,-3,3ZM15,9H5V5h10v4Z") }
    val ArrowBack: ImageVector by lazy { icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20v-2Z") }
    val ArrowForward: ImageVector by lazy { icon("ArrowForward", "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8Z") }
    val OpenInNew: ImageVector by lazy { icon("OpenInNew", "M19,19H5V5h7V3H5c-1.11,0,-2,0.9,-2,2v14c0,1.1,0.89,2,2,2h14c1.1,0,2,-0.9,2,-2v-7h-2v7ZM14,3v2h3.59l-9.83,9.83l1.41,1.41L19,6.41V10h2V3h-7Z") }
    val Share: ImageVector by lazy { icon("Share", "M18,16.08c-0.76,0,-1.44,0.3,-1.96,0.77L8.91,12.7c0.05,-0.23,0.09,-0.46,0.09,-0.7s-0.04,-0.47,-0.09,-0.7l7.05,-4.11c0.54,0.5,1.25,0.81,2.04,0.81c1.66,0,3,-1.34,3,-3s-1.34,-3,-3,-3s-3,1.34,-3,3c0,0.24,0.04,0.47,0.09,0.7L8.04,9.81C7.5,9.31,6.79,9,6,9c-1.66,0,-3,1.34,-3,3s1.34,3,3,3c0.79,0,1.5,-0.31,2.04,-0.81l7.12,4.16c-0.05,0.21,-0.08,0.43,-0.08,0.65c0,1.61,1.31,2.92,2.92,2.92s2.92,-1.31,2.92,-2.92s-1.31,-2.92,-2.92,-2.92Z") }
    val Search: ImageVector by lazy { icon("Search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59,16,11.11,16,9.5C16,5.91,13.09,3,9.5,3S3,5.91,3,9.5S5.91,16,9.5,16c1.61,0,3.09,-0.59,4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5ZM9.5,14C7.01,14,5,11.99,5,9.5S7.01,5,9.5,5S14,7.01,14,9.5S11.99,14,9.5,14Z") }
    val Refresh: ImageVector by lazy { icon("Refresh", "M17.65,6.35C16.2,4.9,14.21,4,12,4c-4.42,0,-7.99,3.58,-7.99,8s3.57,8,7.99,8c3.73,0,6.84,-2.55,7.73,-6h-2.08c-0.82,2.33,-3.04,4,-5.65,4c-3.31,0,-6,-2.69,-6,-6s2.69,-6,6,-6c1.66,0,3.14,0.69,4.22,1.78L13,11h7V4l-2.35,2.35Z") }
    val FileUpload: ImageVector by lazy { icon("FileUpload", "M9,16h6v-6h4l-7,-7l-7,7h4v6ZM5,18h14v2H5v-2Z") }
    val PlayArrow: ImageVector by lazy { icon("PlayArrow", "M8,5v14l11,-7Z") }
    val Stop: ImageVector by lazy { icon("Stop", "M6,6h12v12H6Z") }
    val Info: ImageVector by lazy { icon("Info", "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2ZM13,17h-2v-6h2v6ZM13,9h-2V7h2v2Z") }
    val Help: ImageVector by lazy { icon("Help", "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2ZM13,19h-2v-2h2v2ZM15.07,11.25l-0.9,0.92C13.45,12.9,13,13.5,13,15h-2v-0.5c0,-1.1,0.45,-2.1,1.17,-2.83l1.24,-1.26c0.37,-0.36,0.59,-0.86,0.59,-1.41c0,-1.1,-0.9,-2,-2,-2s-2,0.9,-2,2H8c0,-2.21,1.79,-4,4,-4s4,1.79,4,4c0,0.88,-0.36,1.68,-0.93,2.25Z") }
    val Send: ImageVector by lazy { icon("Send", "M2.01,21L23,12L2.01,3L2,10l15,2l-15,2Z") }
    val Undo: ImageVector by lazy { icon("Undo", "M12.5,8c-2.65,0,-5.05,0.99,-6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16,3.16,-1.88,5.12,-1.88c3.54,0,6.55,2.31,7.6,5.5l2.37,-0.78C21.08,11.03,17.15,8,12.5,8Z") }
    val Redo: ImageVector by lazy { icon("Redo", "M18.4,10.6C16.55,8.99,14.15,8,11.5,8c-4.65,0,-8.58,3.03,-9.96,7.22L3.9,16c1.05,-3.19,4.05,-5.5,7.6,-5.5c1.95,0,3.73,0.72,5.12,1.88L13,16h9V7l-3.6,3.6Z") }
    val Sync: ImageVector by lazy { icon("Sync", "M12,4V1L8,5l4,4V6c3.31,0,6,2.69,6,6c0,1.01,-0.25,1.97,-0.7,2.8l1.46,1.46C19.54,15.03,20,13.57,20,12c0,-4.42,-3.58,-8,-8,-8ZM12,18c-3.31,0,-6,-2.69,-6,-6c0,-1.01,0.25,-1.97,0.7,-2.8L5.24,7.74C4.46,8.97,4,10.43,4,12c0,4.42,3.58,8,8,8v3l4,-4l-4,-4v3Z") }
    val Key: ImageVector by lazy { icon("Key", "M12.65,10C11.83,7.67,9.61,6,7,6c-3.31,0,-6,2.69,-6,6s2.69,6,6,6c2.61,0,4.83,-1.67,5.65,-4H17v4h4v-4h2v-4H12.65ZM7,14c-1.1,0,-2,-0.9,-2,-2s0.9,-2,2,-2s2,0.9,2,2s-0.9,2,-2,2Z") }
    val Label: ImageVector by lazy { icon("Label", "M17.63,5.84C17.27,5.33,16.67,5,16,5L5,5.01C3.9,5.01,3,5.9,3,7v10c0,1.1,0.9,1.99,2,1.99L16,19c0.67,0,1.27,-0.33,1.63,-0.84L22,12l-4.37,-6.16Z") }
    val Notifications: ImageVector by lazy { icon("Notifications", "M12,22c1.1,0,2,-0.9,2,-2h-4c0,1.1,0.89,2,2,2ZM18,16v-5c0,-3.07,-1.64,-5.64,-4.5,-6.32V4c0,-0.83,-0.67,-1.5,-1.5,-1.5s-1.5,0.67,-1.5,1.5v0.68C7.63,5.36,6,7.92,6,11v5l-2,2v1h16v-1l-2,-2Z") }
    val Mail: ImageVector by lazy { icon("Mail", "M20,4H4c-1.1,0,-1.99,0.9,-1.99,2L2,18c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V6c0,-1.1,-0.9,-2,-2,-2ZM20,8l-8,5l-8,-5V6l8,5l8,-5v2Z") }
    val MoreVert: ImageVector by lazy { icon("MoreVert", "M12,8c1.1,0,2,-0.9,2,-2s-0.9,-2,-2,-2s-2,0.9,-2,2s0.9,2,2,2ZM12,10c-1.1,0,-2,0.9,-2,2s0.9,2,2,2s2,-0.9,2,-2s-0.9,-2,-2,-2ZM12,16c-1.1,0,-2,0.9,-2,2s0.9,2,2,2s2,-0.9,2,-2s-0.9,-2,-2,-2Z") }

    /** The icon of that name, for a button that takes its icon from its words (D307, `ButtonIcons`). */
    fun byName(name: String?): ImageVector? = when (name) {
        "SelectAll" -> SelectAll
        "Deselect" -> Deselect
        "Delete" -> Delete
        "Checklist" -> Checklist
        "PauseCircle" -> PauseCircle
        "PlayCircle" -> PlayCircle
        "History" -> History
        "Tune" -> Tune
        "UnfoldLess" -> UnfoldLess
        "UnfoldMore" -> UnfoldMore
        "Edit" -> Edit
        "PlusMinus" -> PlusMinusIcon
        "EditNote" -> EditNote
        "ContentCopy" -> ContentCopy
        "Chat" -> Chat
        "FlipToFront" -> FlipToFront
        "FlipToBack" -> FlipToBack
        "VerticalAlignTop" -> VerticalAlignTop
        "VerticalAlignBottom" -> VerticalAlignBottom
        "Visibility" -> Visibility
        "VisibilityOff" -> VisibilityOff
        "Lock" -> Lock
        "LockOpen" -> LockOpen
        "AddToPhotos" -> AddToPhotos
        "Fullscreen" -> Fullscreen
        "Functions" -> Functions
        "PlaylistAdd" -> PlaylistAdd
        "AddComment" -> AddComment
        "Calculate" -> Calculate
        "MoreTime" -> MoreTime
        "VolumeOff" -> VolumeOff
        "NotificationsOff" -> NotificationsOff
        "TaskAlt" -> TaskAlt
        "DeleteForever" -> DeleteForever
        "FileDownload" -> FileDownload
        "RestoreFromTrash" -> RestoreFromTrash
        "CheckBox" -> CheckBox
        "CheckBoxBlank" -> CheckBoxBlank
        "Description" -> Description
        "TextFormat" -> TextFormat
        "Wallet" -> Wallet
        "Brush" -> Brush
        "Straighten" -> Straighten
        "Mic" -> Mic
        "Event" -> Event
        "PhotoCamera" -> PhotoCamera
        "Videocam" -> Videocam
        "ScreenRecord" -> ScreenRecord
        "Folder" -> Folder
        "NewFolder" -> NewFolder
        "ViewCards" -> ViewCards
        "ViewList" -> ViewList
        "ViewGrid" -> ViewGrid
        "Sort" -> Sort
        "Schedule" -> Schedule
        "Close" -> Close
        "Check" -> Check
        "Add" -> Add
        "Save" -> Save
        "ArrowBack" -> ArrowBack
        "ArrowForward" -> ArrowForward
        "OpenInNew" -> OpenInNew
        "Share" -> Share
        "Search" -> Search
        "Refresh" -> Refresh
        "FileUpload" -> FileUpload
        "PlayArrow" -> PlayArrow
        "Stop" -> Stop
        "Info" -> Info
        "Help" -> Help
        "Send" -> Send
        "Undo" -> Undo
        "Redo" -> Redo
        "Sync" -> Sync
        "Key" -> Key
        "Label" -> Label
        "Notifications" -> Notifications
        "Mail" -> Mail
        "MoreVert" -> MoreVert
        else -> null
    }

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()
}
